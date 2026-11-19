package cn.wenjin.admission.workflow;

import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.common.Jsons;
import cn.wenjin.admission.config.AppProperties;
import cn.wenjin.admission.conversation.ConversationRepository;
import cn.wenjin.admission.data.AdmissionDataRepository;
import cn.wenjin.admission.domain.Domain.*;
import cn.wenjin.admission.engine.RecommendationEngine;
import cn.wenjin.admission.engine.RiskEngine;
import cn.wenjin.admission.profile.ProfileService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AdmissionAdvisorWorkflow {
    private static final Logger log = LoggerFactory.getLogger(AdmissionAdvisorWorkflow.class);
    private final ProfileService profiles;
    private final AdmissionDataRepository data;
    private final RecommendationEngine recommendations;
    private final RiskEngine risks;
    private final ReportGenerator reports;
    private final RunRepository runs;
    private final RunEventStore events;
    private final ConversationRepository conversations;
    private final StringRedisTemplate redis;
    private final ExecutorService executor;
    private final boolean redisQueueEnabled;
    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();
    private final String workerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean redisGroupReady;
    private static final String JOB_STREAM = "admission:jobs";
    private static final String WORKER_GROUP = "advisor-workers";

    public AdmissionAdvisorWorkflow(ProfileService profiles, AdmissionDataRepository data,
                                    RecommendationEngine recommendations, RiskEngine risks,
                                    ReportGenerator reports, RunRepository runs, RunEventStore events,
                                    ConversationRepository conversations, StringRedisTemplate redis,
                                    AppProperties properties) {
        this.profiles = profiles;
        this.data = data;
        this.recommendations = recommendations;
        this.risks = risks;
        this.reports = reports;
        this.runs = runs;
        this.events = events;
        this.conversations = conversations;
        this.redis = redis;
        this.redisQueueEnabled = properties.workflow().redisQueueEnabled();
        this.executor = Executors.newFixedThreadPool(properties.workflow().executorThreads(),
                Thread.ofPlatform().name("advisor-worker-", 0).factory());
    }

    public SubmitResult submit(String userId, String conversationId, String sourceMessageId, String idempotencyKey) {
        conversations.findOwned(conversationId, userId).orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        StudentProfile profile = profiles.current(userId);
        String generatedId = Ids.uuid();
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? Ids.sha256(conversationId + "|" + sourceMessageId + "|" + profile.version()) : idempotencyKey;
        String runId = runs.create(generatedId, conversationId, userId, profile.id(), key, Jsons.write(profile));
        if (runId.equals(generatedId)) {
            boolean queuedInRedis = redisQueueEnabled && events.enqueue(runId, conversationId);
            events.publish(runId, "run_started", Map.of("message", "咨询任务已创建"));
            if (!queuedInRedis) dispatch(runId, () -> execute(runId, conversationId, userId, profile, null), () -> { });
        }
        return new SubmitResult(conversationId, sourceMessageId, runId, runId.equals(generatedId) ? "QUEUED" : "EXISTING");
    }

    public SubmitResult retry(String runId, String userId) {
        RunRepository.RunView view = runs.view(runId, userId);
        return submit(userId, view.conversationId(), "retry-" + runId + "-" + System.nanoTime(), null);
    }

    private void execute(String runId, String conversationId, String userId, StudentProfile profile, String lockedDataset) {
        Ctx ctx = new Ctx(runId, conversationId, profile);
        ctx.datasetVersion = lockedDataset;
        try {
            var savedReport = runs.reportByRun(runId, userId);
            if (savedReport.isPresent()) {
                Report report = savedReport.get();
                ctx.report = report; ctx.reportId = report.id(); ctx.reportVersion = report.version();
                ctx.datasetVersion = report.datasetVersion(); ctx.compliancePassed = report.compliancePassed();
                ctx.complianceIssues = report.complianceIssues(); ctx.reflectionIterations = report.reflectionIterations();
                ctx.status = report.explanationDegraded() ? RunStatus.DEGRADED : RunStatus.COMPLETED;
                ctx.currentNode = "report_completed"; checkpoint(ctx);
                events.publish(runId, "report_completed", Map.of("reportId", report.id(), "status", ctx.status.name()));
                return;
            }
            node(ctx, "profile_analyzing", "正在检查考生资料");
            var completeness = profiles.completeness(ctx.profile);
            ctx.profileComplete = completeness.complete();
            if (!completeness.complete()) {
                ctx.pendingQuestions = List.of(completeness.nextQuestion());
                ctx.status = RunStatus.WAITING_FOR_PROFILE;
                ctx.currentNode = "profile_question_required";
                checkpoint(ctx);
                conversations.addMessage(conversationId, "assistant", "PROFILE_QUESTION", completeness.nextQuestion(),
                        Jsons.write(Map.of("missingFields", completeness.missingFields(), "completeness", completeness.percentage())));
                events.publish(runId, "profile_question_required", Map.of("question", completeness.nextQuestion(),
                        "missingFields", completeness.missingFields()));
                return;
            }
            ensureNotCancelled(ctx);

            node(ctx, "data_resolving", "正在锁定数据版本并检查覆盖");
            if (ctx.datasetVersion == null || ctx.datasetVersion.isBlank())
                ctx.datasetVersion = data.activeDatasetVersion(ctx.profile.province(), ctx.profile.examYear());
            var candidateSet = data.resolveCandidates(ctx.profile.province(), ctx.profile.examYear(),
                    ctx.profile.subjectType(), ctx.profile.batch());
            ctx.candidates = candidateSet.records();
            var coverage = data.coverage(ctx.profile.province(), candidateSet.dataYear());
            if (ctx.candidates.isEmpty()) {
                var availableProvinces = data.availableRecommendationProvinces(ctx.profile.examYear(), ctx.profile.subjectType());
                String availableText = availableProvinces.isEmpty() ? "暂无可演示省份" : String.join("、", availableProvinces);
                ctx.dataWarnings.add(ctx.profile.province() + "截至 " + ctx.profile.examYear()
                        + " 年没有可用于院校推荐的结构化录取数据；当前同科类可演示省份：" + availableText);
            } else if (candidateSet.historicalFallback()) {
                ctx.dataWarnings.add(ctx.profile.examYear() + " 年暂无可用于院校推荐的录取记录，已使用 "
                        + candidateSet.dataYear() + " 年历史录取数据作为参考；该结果不能视为 "
                        + ctx.profile.examYear() + " 年录取线");
            }
            if (ctx.profile.rank() == null && ctx.profile.score() != null) {
                data.scoreToRank(ctx.profile.province(), candidateSet.dataYear(), ctx.profile.subjectType(), ctx.profile.score())
                        .ifPresentOrElse(rank -> ctx.dataWarnings.add((candidateSet.historicalFallback()
                                        ? "根据 " + candidateSet.dataYear() + " 年历史一分一段表，"
                                        : "") + "分数可换算位次约 " + rank + "，当前推荐仍优先等待官方位次"),
                                () -> ctx.dataWarnings.add("没有匹配的一分一段表，不生成位次"));
            }
            checkpoint(ctx);
            ensureNotCancelled(ctx);

            events.publish(runId, "agents_parallel_started", Map.of("message", "正在并行查询招生事实和来源证据"));
            ctx.currentNode = "retrieval_completed";
            ctx.evidence = ctx.candidates.stream().map(record -> new EvidenceItem("admission:" + record.id(),
                    record.dataType(), record.schoolName() + " " + safe(record.majorName()),
                    evidenceExcerpt(record), record.sourceId(), record.sourceUrl(), null, ctx.datasetVersion)).toList();
            events.publish(runId, "retrieval_completed", Map.of("recordCount", ctx.candidates.size(),
                    "evidenceCount", ctx.evidence.size(), "requestedYear", candidateSet.requestedYear(),
                    "dataYear", candidateSet.dataYear(), "historicalFallback", candidateSet.historicalFallback()));
            checkpoint(ctx);
            ensureNotCancelled(ctx);

            node(ctx, "recommendation_started", "正在执行硬规则并计算冲稳保");
            RecommendationEngine.Result result = recommendations.recommend(ctx.profile, ctx.candidates);
            ctx.scored = result.candidates();
            ctx.ruleResults = result.ruleResults();
            ctx.blocked = result.blockedCandidateIds();
            ctx.tierSummary = result.tierSummary();
            events.publish(runId, "rules_completed", Map.of("checked", ctx.ruleResults.size(), "hardBlocked", ctx.blocked.size()));
            events.publish(runId, "recommendation_completed", Map.of("candidateCount", ctx.scored.size(), "tierSummary", ctx.tierSummary));
            checkpoint(ctx);
            ensureNotCancelled(ctx);

            node(ctx, "risk_check_started", "正在检查整张方案风险");
            ctx.riskItems = risks.check(ctx.profile, ctx.scored, ctx.tierSummary);
            ctx.overallRisk = risks.overall(ctx.riskItems);
            events.publish(runId, "risk_check_completed", Map.of("riskCount", ctx.riskItems.size(), "overallRisk", ctx.overallRisk));
            checkpoint(ctx);
            ensureNotCancelled(ctx);

            node(ctx, "report_generating", "正在生成结构化咨询报告");
            String coverageText = "参考数据年份 " + candidateSet.dataYear() + " 年，共 " + coverage.totalRecords() + " 条事实记录";
            if (!ctx.dataWarnings.isEmpty()) coverageText += "；" + String.join("；", ctx.dataWarnings);
            Report report = reports.generate(runId, ctx.profile, ctx.datasetVersion, ctx.scored, ctx.ruleResults,
                    ctx.riskItems, ctx.evidence, ctx.tierSummary, coverageText);
            ctx.report = report;
            ctx.reportId = report.id();
            ctx.reportVersion = report.version();
            ctx.compliancePassed = report.compliancePassed();
            ctx.complianceIssues = report.complianceIssues();
            ctx.reflectionIterations = report.reflectionIterations();
            events.publish(runId, "self_check_round", Map.of("round", 1, "passed", report.compliancePassed(),
                    "issues", report.complianceIssues()));
            if (!report.compliancePassed()) throw new IllegalStateException("报告合规检查未通过：" + String.join("；", report.complianceIssues()));
            String recommendationId = runs.saveRecommendation(runId, ctx.profile.id(), ctx.datasetVersion, ctx.scored, ctx.riskItems);
            runs.saveReport(report, userId, recommendationId);
            ctx.status = report.explanationDegraded() ? RunStatus.DEGRADED : RunStatus.COMPLETED;
            ctx.currentNode = "report_completed";
            checkpoint(ctx);
            conversations.addMessage(conversationId, "assistant", "REPORT_READY",
                    "结构化分析已完成。报告包含冲稳保建议、风险和来源，请重点查看数据覆盖不足项。",
                    Jsons.write(Map.of("runId", runId, "reportId", report.id())));
            if (report.explanationDegraded()) events.publish(runId, "degraded_notice", Map.of("message", "解释服务使用本地降级模板，规则和数据结果不受影响"));
            events.publish(runId, "report_completed", Map.of("reportId", report.id(), "status", ctx.status.name()));
        } catch (CancelledException ignored) {
            ctx.status = RunStatus.CANCELLED;
            ctx.currentNode = "cancelled";
            checkpoint(ctx);
            events.publish(runId, "run_failed", Map.of("category", "CANCELLED", "message", "任务已取消"));
        } catch (Exception exception) {
            log.error("Advisor run {} failed at {}", runId, ctx.currentNode, exception);
            ctx.status = RunStatus.FAILED;
            ctx.error = safeMessage(exception);
            checkpoint(ctx);
            events.publish(runId, "run_failed", Map.of("category", "WORKFLOW_ERROR", "message", ctx.error));
        }
    }

    private void node(Ctx ctx, String node, String message) {
        ctx.currentNode = node;
        ctx.status = RunStatus.RUNNING;
        checkpoint(ctx);
        events.publish(ctx.runId, node, Map.of("message", message));
    }

    private void checkpoint(Ctx ctx) { runs.checkpoint(ctx.state()); }
    private void ensureNotCancelled(Ctx ctx) { if (runs.cancelRequested(ctx.runId)) throw new CancelledException(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String safeMessage(Exception e) { String value = e.getMessage(); return value == null ? "任务执行失败" : value.substring(0, Math.min(800, value.length())); }
    private static String evidenceExcerpt(AdmissionRecord r) {
        return r.year() + "年 " + r.province() + " " + safe(r.subjectType()) + " " + safe(r.batch())
                + "，最低分 " + (r.minScore() == null ? "未公开" : r.minScore())
                + "，最低位次 " + (r.minRankEnd() == null ? "原始来源未公开" : r.minRankEnd());
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverInterruptedRuns() {
        for (RunRepository.RecoverableRun run : runs.recoverableRuns()) {
            events.publish(run.id(), "run_started", Map.of("message", "服务重启后恢复未完成任务", "recovered", true));
            boolean queuedInRedis = redisQueueEnabled && events.enqueue(run.id(), run.conversationId());
            if (!queuedInRedis) dispatchRecovered(run, () -> { });
        }
    }

    @Scheduled(fixedDelayString = "${app.workflow.redis-poll-ms:750}")
    void consumeRedisJobs() {
        if (!redisQueueEnabled) return;
        try {
            ensureRedisGroup();
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(WORKER_GROUP, workerName), StreamReadOptions.empty().count(4),
                    StreamOffset.create(JOB_STREAM, ReadOffset.lastConsumed()));
            if (records == null) return;
            for (MapRecord<String, Object, Object> record : records) {
                String runId = String.valueOf(record.getValue().get("runId"));
                Runnable acknowledge = () -> {
                    try { redis.opsForStream().acknowledge(JOB_STREAM, WORKER_GROUP, record.getId()); }
                    catch (Exception exception) { log.warn("Could not acknowledge Redis job {}", runId); }
                };
                runs.recoverableRun(runId).ifPresentOrElse(run -> dispatchRecovered(run, acknowledge), acknowledge);
            }
        } catch (Exception exception) {
            redisGroupReady = false;
            log.debug("Redis worker poll unavailable: {}", exception.getMessage());
        }
    }

    private void ensureRedisGroup() {
        if (redisGroupReady) return;
        StreamOperations<String, Object, Object> stream = redis.opsForStream();
        if (redisGroupExists(stream)) {
            redisGroupReady = true;
            return;
        }
        try { stream.createGroup(JOB_STREAM, ReadOffset.from("0-0"), WORKER_GROUP); }
        catch (Exception exception) {
            // Spring Data may wrap BUSYGROUP without retaining it in the outer message.
            if (!redisGroupExists(stream)) throw exception;
        }
        redisGroupReady = true;
    }

    private static boolean redisGroupExists(StreamOperations<String, Object, Object> stream) {
        try {
            var groups = stream.groups(JOB_STREAM);
            return groups != null && groups.stream().anyMatch(group -> WORKER_GROUP.equals(group.groupName()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void dispatchRecovered(RunRepository.RecoverableRun run, Runnable completion) {
        dispatch(run.id(), () -> {
            try {
                AdvisorRunState state = Jsons.read(run.stateOrProfileJson(), AdvisorRunState.class);
                execute(run.id(), run.conversationId(), run.userId(), state.profile(), state.datasetVersion());
            } catch (Exception stateError) {
                StudentProfile profile = Jsons.read(run.stateOrProfileJson(), StudentProfile.class);
                execute(run.id(), run.conversationId(), run.userId(), profile, null);
            }
        }, completion);
    }

    private void dispatch(String runId, Runnable work, Runnable completion) {
        if (!activeRuns.add(runId)) return;
        executor.submit(() -> {
            try { work.run(); }
            catch (Exception exception) { log.error("Cannot dispatch advisor run {}", runId, exception); }
            finally { activeRuns.remove(runId); completion.run(); }
        });
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }

    public record SubmitResult(String conversationId, String messageId, String runId, String status) { }
    private static final class CancelledException extends RuntimeException { }

    private static final class Ctx {
        final String runId; final String conversationId; final StudentProfile profile;
        boolean profileComplete; List<String> pendingQuestions = List.of(); String datasetVersion;
        List<String> dataWarnings = new ArrayList<>(); List<EvidenceItem> evidence = List.of();
        List<RuleResult> ruleResults = List.of(); List<String> blocked = List.of();
        List<AdmissionRecord> candidates = List.of(); List<ScoredCandidate> scored = List.of();
        TierSummary tierSummary = new TierSummary(0, 0, 0); List<RiskItem> riskItems = List.of();
        RiskLevel overallRisk = RiskLevel.LOW; Report report; String reportId; int reportVersion;
        boolean compliancePassed; List<String> complianceIssues = List.of(); int reflectionIterations;
        RunStatus status = RunStatus.QUEUED; String currentNode = "queued"; String error;
        Ctx(String runId, String conversationId, StudentProfile profile){this.runId=runId;this.conversationId=conversationId;this.profile=profile;}
        AdvisorRunState state(){return new AdvisorRunState(runId,conversationId,profile.userId(),profile.id(),profile,profileComplete,pendingQuestions,datasetVersion,List.copyOf(dataWarnings),evidence,ruleResults,blocked,candidates,scored,tierSummary,riskItems,overallRisk,report,reportId,reportVersion,compliancePassed,complianceIssues,reflectionIterations,status,currentNode,error);}
    }
}
