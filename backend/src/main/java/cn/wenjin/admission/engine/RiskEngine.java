package cn.wenjin.admission.engine;

import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.domain.Domain.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RiskEngine {
    public List<RiskItem> check(StudentProfile p,List<ScoredCandidate> items,TierSummary summary){List<RiskItem>x=new ArrayList<>();if(items.isEmpty())add(x,"NO_CANDIDATE",RiskLevel.BLOCKING,"没有可推荐候选","当前数据覆盖或硬规则过滤后没有可靠候选，不生成虚假推荐。",true);if(summary.safe()<2)add(x,"INSUFFICIENT_SAFE",RiskLevel.HIGH,"保底项不足","当前方案少于 2 个保底候选，正式填报前需要补充可接受的保底项。",false);if(summary.challenge()>Math.max(2,items.size()/2))add(x,"TOO_MANY_CHALLENGE",RiskLevel.HIGH,"冲刺项偏多","冲刺项占比过高，方案梯度可能不合理。",false);long missingRank=items.stream().filter(c->c.record().rankUnavailable()||c.record().minRankEnd()==null).count();if(missingRank>0)add(x,"RANK_MISSING",RiskLevel.MEDIUM,"部分来源缺少位次",missingRank+" 个候选只能按分数边界参考，不能展示虚构位次。",false);long sameSchool=items.stream().collect(java.util.stream.Collectors.groupingBy(c->c.record().schoolName(),java.util.stream.Collectors.counting())).values().stream().mapToLong(v->Math.max(0,v-3)).sum();if(sameSchool>0)add(x,"SCHOOL_CONCENTRATION",RiskLevel.MEDIUM,"同一学校重复较多","候选集中在少数学校，建议扩大可接受城市和学校范围。",false);if(items.stream().anyMatch(c->c.record().planCount()==null))add(x,"PLAN_MISSING",RiskLevel.MEDIUM,"招生计划覆盖不足","部分候选缺少本年度招生计划，必须回官网核验。",false);return List.copyOf(x);}
    private static void add(List<RiskItem>x,String code,RiskLevel l,String title,String detail,boolean block){x.add(new RiskItem(Ids.uuid(),null,code,l,title,detail,List.of(),block));}
    public RiskLevel overall(List<RiskItem>x){return x.stream().map(RiskItem::level).max(Comparator.comparingInt(Enum::ordinal)).orElse(RiskLevel.LOW);}
}

