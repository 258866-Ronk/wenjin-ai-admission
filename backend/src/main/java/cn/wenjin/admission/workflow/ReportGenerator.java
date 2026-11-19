package cn.wenjin.admission.workflow;

import cn.wenjin.admission.ai.LlmGateway;
import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.common.Jsons;
import cn.wenjin.admission.domain.Domain;
import cn.wenjin.admission.domain.Domain.*;
import cn.wenjin.admission.engine.ComplianceEngine;
import cn.wenjin.admission.engine.RiskEngine;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ReportGenerator {
    private final LlmGateway llm;private final ComplianceEngine compliance;private final RiskEngine risks;
    public ReportGenerator(LlmGateway llm,ComplianceEngine compliance,RiskEngine risks){this.llm=llm;this.compliance=compliance;this.risks=risks;}
    public Report generate(String runId,StudentProfile p,String dataset,List<ScoredCandidate> items,List<RuleResult> rules,List<RiskItem> riskItems,List<EvidenceItem> evidence,TierSummary summary,String coverage){boolean degraded=false;String comment;try{var response=llm.chat(LlmGateway.LlmRequest.single("只能解释确定性结果，不得新增事实或录取承诺。","请用两句话说明数据覆盖与风险。覆盖："+coverage+"，冲稳保："+summary));comment=response.content();degraded=response.degraded();}catch(Exception e){comment="已基于结构化招生数据和硬规则完成分析。当前样本覆盖有限，建议扩大地域范围并回官方渠道核验。";degraded=true;}List<ScoredCandidate> top=items.stream().limit(12).toList();RecommendationPlan conservative=plan("CONSERVATIVE",top,Comparator.comparingInt(x->tierOrderSafe(x.tier())));RecommendationPlan balanced=plan("BALANCED",top,Comparator.comparingDouble(ScoredCandidate::totalScore).reversed());RecommendationPlan progressive=plan("PROGRESSIVE",top,Comparator.comparingInt(x->tierOrderChallenge(x.tier())));Report draft=new Report(Ids.uuid(),runId,1,"高考志愿智能咨询报告",p,dataset,coverage,comment,risks.overall(riskItems).name(),summary,conservative,balanced,progressive,rules,riskItems,evidence,List.of(p.annualTuitionBudget()==null?"尚未提供年度学费预算。":"年度学费预算为 "+p.annualTuitionBudget()+" 元；缺失学费的候选需另行核验。"),List.of("意向专业："+String.join("、",p.preferredMajors()),"意向城市："+String.join("、",p.preferredCities())),List.of("核对省级教育考试院最新计划和投档规则","核对高校招生章程、选科和体检限制","核对专业学费、校区与调剂范围"),Domain.DISCLAIMER,false,List.of(),0,degraded);ComplianceEngine.Result check=compliance.inspect(Jsons.write(draft));return new Report(draft.id(),draft.runId(),draft.version(),draft.title(),draft.profile(),draft.datasetVersion(),draft.dataCoverage(),draft.overallComment(),draft.overallRiskLevel(),draft.tierSummary(),draft.conservativePlan(),draft.balancedPlan(),draft.progressivePlan(),draft.ruleResults(),draft.riskItems(),draft.evidence(),draft.budgetAnalysis(),draft.preferenceAnalysis(),draft.verificationChecklist(),draft.disclaimer(),check.passed(),check.issues(),1,degraded);}
    private static RecommendationPlan plan(String strategy,List<ScoredCandidate> items,Comparator<ScoredCandidate> c){List<ScoredCandidate> sorted=items.stream().sorted(c).limit(10).toList();TierSummary s=new TierSummary((int)sorted.stream().filter(x->x.tierGroup().equals("冲")).count(),(int)sorted.stream().filter(x->x.tierGroup().equals("稳")).count(),(int)sorted.stream().filter(x->x.tierGroup().equals("保")).count());return new RecommendationPlan(strategy,sorted,s,List.of());}
    private static int tierOrderSafe(String t){return switch(t){case"保底"->0;case"稳妥"->1;case"偏稳"->2;case"普通冲"->3;default->4;};}private static int tierOrderChallenge(String t){return switch(t){case"普通冲"->0;case"高冲"->1;case"偏稳"->2;case"稳妥"->3;default->4;};}
}
