package cn.wenjin.admission.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {
    }

    public static final String DISCLAIMER = "本系统提供的是基于公开数据和用户资料的辅助分析，不构成录取承诺，也不能替代省级教育考试院、高校招生章程及官方志愿填报系统。招生政策和计划可能发生变化，正式填报前请再次核对最新官方信息。";

    public enum Intent {
        GENERAL_QA, PROFILE_UPDATE, SCHOOL_QUERY, MAJOR_QUERY, CITY_QUERY, POLICY_QUERY,
        GENERATE_RECOMMENDATION, CHECK_VOLUNTEER_PLAN, REFINE_REPORT, EXPLAIN_RECOMMENDATION, UNKNOWN
    }

    public enum RunStatus {
        QUEUED, RUNNING, WAITING_FOR_PROFILE, COMPLETED, DEGRADED, FAILED, CANCELLED
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, BLOCKING
    }

    public record FieldValue(Object value, double confidence, String sourceMessageId, Instant updatedAt) {
    }

    public record StudentProfile(
            String id, String userId, String province, Integer examYear, Integer score, Integer rank,
            String rankSource, String subjectType, String primarySubject, List<String> secondarySubjects,
            String batch, String candidateCategory, Integer annualTuitionBudget, List<String> preferredCities,
            List<String> excludedCities, List<String> preferredMajors, List<String> excludedMajors,
            List<String> schoolLevelPreferences, String employmentDirection, Boolean acceptPrivate,
            Boolean acceptCooperative, Boolean acceptVocational, List<String> medicalRestrictions,
            String riskPreference, String specialRequirements, Map<String, FieldValue> fieldMeta, int version,
            Instant updatedAt) {

        public static StudentProfile empty(String id, String userId) {
            return new StudentProfile(id, userId, null, null, null, null, null, null, null, List.of(),
                    null, "普通类", null, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                    null, null, null, List.of(), "BALANCED", null, Map.of(), 1, Instant.now());
        }

        public StudentProfile withUpdates(ProfilePatch patch, String messageId) {
            Map<String, FieldValue> meta = new LinkedHashMap<>(fieldMeta == null ? Map.of() : fieldMeta);
            Instant now = Instant.now();
            patch.presentValues().forEach((key, value) -> meta.put(key, new FieldValue(value, 0.96, messageId, now)));
            boolean examContextChanged = province != null && patch.province != null && !province.equals(patch.province)
                    || examYear != null && patch.examYear != null && !examYear.equals(patch.examYear)
                    || subjectType != null && patch.subjectType != null && !subjectType.equals(patch.subjectType);
            // 切换省份、年份或科类时，旧位次不能沿用；新消息未给分数时旧分数也应失效。
            Integer nextScore = patch.score != null ? patch.score : examContextChanged ? null : score;
            Integer nextRank = patch.rank != null ? patch.rank : examContextChanged ? null : rank;
            String nextRankSource = patch.rank != null ? "USER_PROVIDED" : examContextChanged ? null : rankSource;
            return new StudentProfile(id, userId, patch.province != null ? patch.province : province,
                    patch.examYear != null ? patch.examYear : examYear, nextScore,
                    nextRank, nextRankSource,
                    patch.subjectType != null ? patch.subjectType : subjectType,
                    patch.primarySubject != null ? patch.primarySubject : primarySubject,
                    merge(secondarySubjects, patch.secondarySubjects), patch.batch != null ? patch.batch : batch,
                    patch.candidateCategory != null ? patch.candidateCategory : candidateCategory,
                    patch.annualTuitionBudget != null ? patch.annualTuitionBudget : annualTuitionBudget,
                    merge(preferredCities, patch.preferredCities), merge(excludedCities, patch.excludedCities),
                    merge(preferredMajors, patch.preferredMajors), merge(excludedMajors, patch.excludedMajors),
                    merge(schoolLevelPreferences, patch.schoolLevelPreferences),
                    patch.employmentDirection != null ? patch.employmentDirection : employmentDirection,
                    patch.acceptPrivate != null ? patch.acceptPrivate : acceptPrivate,
                    patch.acceptCooperative != null ? patch.acceptCooperative : acceptCooperative,
                    patch.acceptVocational != null ? patch.acceptVocational : acceptVocational,
                    merge(medicalRestrictions, patch.medicalRestrictions),
                    patch.riskPreference != null ? patch.riskPreference : riskPreference,
                    patch.specialRequirements != null ? patch.specialRequirements : specialRequirements, meta,
                    version + 1, now);
        }

        private static List<String> merge(List<String> existing, List<String> replacement) {
            return replacement == null ? (existing == null ? List.of() : existing) : List.copyOf(replacement);
        }
    }

    public record ProfilePatch(
            String province, Integer examYear, Integer score, Integer rank, String subjectType,
            String primarySubject, List<String> secondarySubjects, String batch, String candidateCategory,
            Integer annualTuitionBudget, List<String> preferredCities, List<String> excludedCities,
            List<String> preferredMajors, List<String> excludedMajors, List<String> schoolLevelPreferences,
            String employmentDirection, Boolean acceptPrivate, Boolean acceptCooperative,
            Boolean acceptVocational, List<String> medicalRestrictions, String riskPreference,
            String specialRequirements) {
        public Map<String, Object> presentValues() {
            Map<String, Object> values = new LinkedHashMap<>();
            put(values, "province", province);
            put(values, "examYear", examYear);
            put(values, "score", score);
            put(values, "rank", rank);
            put(values, "subjectType", subjectType);
            put(values, "primarySubject", primarySubject);
            put(values, "secondarySubjects", secondarySubjects);
            put(values, "batch", batch);
            put(values, "candidateCategory", candidateCategory);
            put(values, "annualTuitionBudget", annualTuitionBudget);
            put(values, "preferredCities", preferredCities);
            put(values, "excludedCities", excludedCities);
            put(values, "preferredMajors", preferredMajors);
            put(values, "excludedMajors", excludedMajors);
            put(values, "acceptPrivate", acceptPrivate);
            put(values, "acceptCooperative", acceptCooperative);
            put(values, "acceptVocational", acceptVocational);
            put(values, "medicalRestrictions", medicalRestrictions);
            put(values, "riskPreference", riskPreference);
            return values;
        }

        private static void put(Map<String, Object> values, String key, Object value) {
            if (value != null) values.put(key, value);
        }
    }

    public record Completeness(boolean complete, int percentage, List<String> missingFields, String nextQuestion) {
    }

    public record AdmissionRecord(
            String id, String businessKey, String datasetVersion, String province, int year, String subjectType,
            String batch, String schoolName, String schoolCode, List<String> schoolTags, String city,
            String dataType, String majorName, String majorCode, String majorGroup, String electiveRequirement,
            List<String> requiredSubjects, List<String> anySubjects, Integer minScore, BigDecimal avgScore,
            Integer maxScore, Integer minRankStart, Integer minRankEnd, Integer planCount, Integer tuition,
            String formalScoreScope, boolean rankUnavailable, boolean scoreOnly, String admissionType,
            String admissionSubtype, List<String> medicalRestrictions, String sourceId, String sourceUrl,
            String sourceQuality, Instant sourcePublishedAt, List<String> cautions) {
    }

    public record Source(
            String id, String name, String publisher, String sourceUrl, String sourceQuality,
            Instant sourcePublishedAt, Instant retrievedAt, String checksum, List<String> cautions) {
    }

    public record RuleResult(
            String id, String candidateId, String ruleCode, boolean passed, boolean hardRule,
            String message, List<String> evidenceIds) {
    }

    public record ScoreBreakdown(
            double rankFit, double majorPreference, double cityPreference, double schoolPreference,
            double budgetFit, double dataQuality, double riskPenalty) {
        public double total() {
            return rankFit + majorPreference + cityPreference + schoolPreference + budgetFit + dataQuality - riskPenalty;
        }
    }

    public record ScoredCandidate(
            AdmissionRecord record, String tier, String tierGroup, ScoreBreakdown scoreBreakdown,
            double totalScore, List<String> reasons, List<String> riskNotes, List<String> evidenceIds,
            List<String> ruleResultIds, List<String> sourceIds) {
    }

    public record TierSummary(int challenge, int stable, int safe) {
    }

    public record RiskItem(
            String id, String candidateId, String code, RiskLevel level, String title, String detail,
            List<String> evidenceIds, boolean blocking) {
    }

    public record EvidenceItem(
            String id, String type, String title, String excerpt, String sourceId, String sourceUrl,
            String ruleResultId, String datasetVersion) {
    }

    public record RecommendationPlan(
            String strategy, List<ScoredCandidate> items, TierSummary tierSummary, List<RiskItem> risks) {
    }

    public record Report(
            String id, String runId, int version, String title, StudentProfile profile, String datasetVersion,
            String dataCoverage, String overallComment, String overallRiskLevel, TierSummary tierSummary,
            RecommendationPlan conservativePlan, RecommendationPlan balancedPlan,
            RecommendationPlan progressivePlan, List<RuleResult> ruleResults, List<RiskItem> riskItems,
            List<EvidenceItem> evidence, List<String> budgetAnalysis, List<String> preferenceAnalysis,
            List<String> verificationChecklist, String disclaimer, boolean compliancePassed,
            List<String> complianceIssues, int reflectionIterations, boolean explanationDegraded) {
    }

    public record RunEvent(long id, String event, Instant timestamp, Map<String, Object> data) {
    }

    public record AdvisorRunState(
            String runId, String conversationId, String userId, String profileId, StudentProfile profile,
            boolean profileComplete, List<String> pendingQuestions, String datasetVersion,
            List<String> dataWarnings, List<EvidenceItem> evidenceItems, List<RuleResult> ruleResults,
            List<String> hardBlockedCandidateIds, List<AdmissionRecord> candidates,
            List<ScoredCandidate> scoredCandidates, TierSummary tierSummary, List<RiskItem> riskItems,
            RiskLevel overallRiskLevel, Report reportDraft, String reportId, int reportVersion,
            boolean compliancePassed, List<String> complianceIssues, int reflectionIterations,
            RunStatus status, String currentNode, String errorMessage) {
        public static AdvisorRunState initial(String runId, String conversationId, StudentProfile profile) {
            return new AdvisorRunState(runId, conversationId, profile.userId(), profile.id(), profile, false,
                    new ArrayList<>(), null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new TierSummary(0, 0, 0),
                    new ArrayList<>(), RiskLevel.LOW, null, null, 0, false, new ArrayList<>(), 0,
                    RunStatus.QUEUED, "queued", null);
        }
    }
}
