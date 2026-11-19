package cn.wenjin.admission.engine;

import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.domain.Domain.AdmissionRecord;
import cn.wenjin.admission.domain.Domain.RuleResult;
import cn.wenjin.admission.domain.Domain.StudentProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class PolicyRuleEngine {
    public List<RuleResult> evaluate(StudentProfile p, AdmissionRecord r){
        List<RuleResult> out=new ArrayList<>();
        add(out,r,"PROVINCE",p.province().equals(r.province()),true,"招生省份必须匹配");
        // 历史数据可作为明确标注的参考；未来年份数据仍然属于硬规则错误。
        boolean yearAllowed=p.examYear()!=null&&r.year()<=p.examYear();
        String yearMessage=yearAllowed&&r.year()<p.examYear()
                ? "使用 "+r.year()+" 年历史录取数据参考，不能视为 "+p.examYear()+" 年录取线"
                : "数据年份不得晚于本次锁定年份";
        add(out,r,"YEAR",yearAllowed,true,yearMessage);
        add(out,r,"DATA_SCOPE",!contains(r.formalScoreScope(),"special-path","control-line"),true,"特殊路径或控制线不能进入普通批推荐");
        if(!r.requiredSubjects().isEmpty())add(out,r,"SUBJECT_REQUIRED",subjects(p).containsAll(r.requiredSubjects()),true,"必须满足全部必选科目："+String.join("、",r.requiredSubjects()));
        if(!r.anySubjects().isEmpty())add(out,r,"SUBJECT_ANY",r.anySubjects().stream().anyMatch(subjects(p)::contains),true,"至少选择一科："+String.join("、",r.anySubjects()));
        if(Boolean.FALSE.equals(p.acceptCooperative()))add(out,r,"COOPERATIVE",!contains(join(r),"中外合作","学分互认"),true,"用户不接受中外合作或学分互认项目");
        if(Boolean.FALSE.equals(p.acceptPrivate()))add(out,r,"PRIVATE",!contains(join(r),"民办","独立学院"),true,"用户不接受民办院校");
        if(p.annualTuitionBudget()!=null&&r.tuition()!=null)add(out,r,"BUDGET",r.tuition()<=p.annualTuitionBudget(),true,"学费不得超过年度预算");
        if(!p.medicalRestrictions().isEmpty()&&!r.medicalRestrictions().isEmpty())add(out,r,"MEDICAL",p.medicalRestrictions().stream().noneMatch(x->r.medicalRestrictions().stream().anyMatch(y->y.contains(x))),true,"体检限制需要逐项核验");
        return List.copyOf(out);
    }
    public boolean allowed(List<RuleResult> results){return results.stream().noneMatch(x->x.hardRule()&&!x.passed());}
    private static void add(List<RuleResult> out,AdmissionRecord r,String code,boolean passed,boolean hard,String message){out.add(new RuleResult(Ids.uuid(),r.id(),code,passed,hard,message,List.of("source:"+r.sourceId())));}
    private static List<String> subjects(StudentProfile p){List<String> s=new ArrayList<>();if(p.primarySubject()!=null)s.add(p.primarySubject());s.addAll(p.secondarySubjects());return s;}
    private static boolean contains(String text,String... needles){if(text==null)return false;String n=text.toLowerCase(Locale.ROOT);for(String x:needles)if(n.contains(x.toLowerCase(Locale.ROOT)))return true;return false;}
    private static String join(AdmissionRecord r){return String.join("  ",safe(r.schoolName()),safe(r.majorName()),safe(r.admissionType()),safe(r.admissionSubtype()),String.join("  ",r.schoolTags()));}
    private static String safe(String s){return s==null?"":s;}
}
