package cn.wenjin.admission.engine;

import cn.wenjin.admission.common.Jsons;
import cn.wenjin.admission.domain.Domain.*;
import cn.wenjin.admission.engine.ThresholdRepository.Threshold;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class RecommendationEngine {
    private final ThresholdRepository thresholds; private final PolicyRuleEngine rules;
    public RecommendationEngine(ThresholdRepository thresholds,PolicyRuleEngine rules){this.thresholds=thresholds;this.rules=rules;}
    public Result recommend(StudentProfile p,List<AdmissionRecord> records){
        List<RuleResult> allRules=new ArrayList<>();List<String> blocked=new ArrayList<>();List<ScoredCandidate> scored=new ArrayList<>();List<Threshold> ts=thresholds.find(p.province(),p.examYear(),p.subjectType());
        for(AdmissionRecord r:records){List<RuleResult> rr=rules.evaluate(p,r);allRules.addAll(rr);if(!rules.allowed(rr)){blocked.add(r.id());continue;}String tier=tier(p,r,ts);ScoreBreakdown b=breakdown(p,r,tier);List<String> reasons=reasons(p,r,tier);List<String> risks=new ArrayList<>(r.cautions());if(r.rankUnavailable())risks.add("原始来源未公开最低位次，不能据此生成位次事实。");scored.add(new ScoredCandidate(r,tier,group(tier),b,b.total(),reasons,List.copyOf(risks),List.of("admission:"+r.id()),rr.stream().map(RuleResult::id).toList(),List.of(r.sourceId())));}
        scored.sort(Comparator.comparingDouble(ScoredCandidate::totalScore).reversed());return new Result(List.copyOf(scored),List.copyOf(allRules),List.copyOf(blocked),summary(scored));
    }
    private static String tier(StudentProfile p,AdmissionRecord r,List<Threshold> ts){
        if(p.rank()!=null&&r.minRankEnd()!=null){int gap=p.rank()-r.minRankEnd();return ts.stream().filter(t->gap>=t.minRankGap()&&gap<=t.maxRankGap()).map(Threshold::tier).findFirst().orElse("数据不足");}
        if(p.score()!=null&&r.minScore()!=null){int gap=p.score()-r.minScore();return ts.stream().filter(t->gap>=t.minScoreGap()&&gap<=t.maxScoreGap()).map(Threshold::tier).findFirst().orElse("分数参考");}
        return "数据不足";
    }
    private static ScoreBreakdown breakdown(StudentProfile p,AdmissionRecord r,String tier){double rank=switch(tier){case"保底"->58;case"稳妥"->54;case"偏稳"->48;case"普通冲"->38;case"高冲"->24;default->18;};double major=hit(r.majorName(),p.preferredMajors())?12:p.preferredMajors().isEmpty()?6:1;double city=hit(r.city(),p.preferredCities())?10:p.preferredCities().isEmpty()?5:1;double school=hit(String.join("  ",r.schoolTags()),p.schoolLevelPreferences())?8:4;double budget=r.tuition()==null?3:p.annualTuitionBudget()==null||r.tuition()<=p.annualTuitionBudget()?8:0;double quality=r.sourceQuality().contains("official")?10:4;double penalty=r.rankUnavailable()?8:0;return new ScoreBreakdown(rank,major,city,school,budget,quality,penalty);}
    private static List<String> reasons(StudentProfile p,AdmissionRecord r,String tier){List<String>x=new ArrayList<>();x.add("按锁定数据版本分层为“"+tier+"”");if(hit(r.majorName(),p.preferredMajors()))x.add("专业与意向方向匹配");if(hit(r.city(),p.preferredCities()))x.add("城市符合地域偏好");if(r.minRankEnd()!=null)x.add("参考最低位次为 "+r.minRankEnd()+"，来源直接公开");else x.add("仅作分数边界参考，原始来源未公开最低位次");return x;}
    private static boolean hit(String value,List<String> prefs){if(value==null)return false;return prefs.stream().anyMatch(x->value.contains(x)||x.contains(value));}
    private static String group(String tier){return switch(tier){case"高冲","普通冲"->"冲";case"偏稳","稳妥"->"稳";case"保底"->"保";default->"参考";};}
    private static TierSummary summary(List<ScoredCandidate>s){return new TierSummary((int)s.stream().filter(x->x.tierGroup().equals("冲")).count(),(int)s.stream().filter(x->x.tierGroup().equals("稳")).count(),(int)s.stream().filter(x->x.tierGroup().equals("保")).count());}
    public record Result(List<ScoredCandidate> candidates,List<RuleResult> ruleResults,List<String> blockedCandidateIds,TierSummary tierSummary){}
}

