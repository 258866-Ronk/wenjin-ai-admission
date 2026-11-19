package cn.wenjin.admission.profile;

import cn.wenjin.admission.domain.Domain.Completeness;
import cn.wenjin.admission.domain.Domain.ProfilePatch;
import cn.wenjin.admission.domain.Domain.StudentProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.time.Year;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProfileService {
    private static final List<String> PROVINCES = List.of("北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(20\\d{2})(?:\\s*年)?(?!\\d)");
    private static final Pattern SCORE = Pattern.compile("(?:考了|高考|分数(?:是|为)?|总分(?:是|为)?)\\s*(\\d{3})\\s*分?");
    private static final Pattern RANK = Pattern.compile("(?:位次|排名)(?:是|为|大约|约)?\\s*(\\d{1,7})");
    private static final Pattern BUDGET = Pattern.compile("(?:学费|预算)(?:一年|每年|年)?(?:大约|约|是|为)?\\s*(\\d+(?:\\.\\d+)?)\\s*(万|元)?");
    private final ProfileRepository repository;

    public ProfileService(ProfileRepository repository) { this.repository = repository; }
    public StudentProfile current(String userId){ return repository.findByUserId(userId).orElseThrow(); }
    public StudentProfile save(String userId, ProfilePatch patch, String messageId){ return repository.save(current(userId).withUpdates(patch,messageId)); }

    public ProfilePatch extract(String message) {
        String province=PROVINCES.stream().filter(message::contains).findFirst().orElse(null);
        Integer year=number(YEAR,message);
        // 用户常用“今年”回答追问，直接锁定系统当前年份，避免反复询问同一字段。
        if(year==null&&containsAny(message,"今年","本届"))year=Year.now().getValue();
        Integer score=number(SCORE,message); Integer rank=number(RANK,message);
        String primary=message.contains("物理")?"物理":message.contains("历史")?"历史":null;
        String subjectType=primary==null?null:primary+"类";
        List<String> secondary=new ArrayList<>(); for(String s:List.of("化学","生物","政治","地理")) if(message.contains(s)) secondary.add(s);
        String batch=message.contains("专科")?"专科批":message.contains("本科")?"本科批":"本科批";
        Integer budget=budget(message); List<String> cities=matches(message,List.of("武汉","杭州","南京","上海","北京","深圳","广州","成都","重庆","西安","长沙","南昌","济南","赣州"));
        List<String> majors=matches(message,List.of("计算机","软件工程","人工智能","数据科学","电子信息","自动化","电气","医学","法学","金融","师范"));
        Boolean cooperative=null;
        if(containsAny(message,"不接受中外合作","不要中外合作"))cooperative=Boolean.FALSE;
        else if(containsAny(message,"接受中外合作","可以中外合作"))cooperative=Boolean.TRUE;
        Boolean privateSchool=null;
        if(containsAny(message,"不接受民办","不要民办"))privateSchool=Boolean.FALSE;
        else if(containsAny(message,"接受民办","可以民办"))privateSchool=Boolean.TRUE;
        List<String> medical=matches(message,List.of("色盲","色弱","视力受限","身高受限"));
        String risk=message.contains("保守")?"CONSERVATIVE":message.contains("进取")?"PROGRESSIVE":null;
        return new ProfilePatch(province,year,score,rank,subjectType,primary,secondary.isEmpty()?null:secondary,batch,"普通类",budget,cities.isEmpty()?null:cities,null,majors.isEmpty()?null:majors,null,null,null,privateSchool,cooperative,null,medical.isEmpty()?null:medical,risk,null);
    }

    public Completeness completeness(StudentProfile profile){
        List<String> missing=new ArrayList<>(); if(blank(profile.province()))missing.add("省份"); if(profile.examYear()==null)missing.add("高考年份"); if(profile.score()==null&&profile.rank()==null)missing.add("分数或位次"); if(blank(profile.subjectType()))missing.add("科类/选科"); if(blank(profile.batch()))missing.add("目标批次");
        int percentage=(int)Math.round((5-missing.size())/5.0*100); String q=missing.isEmpty()?null:switch(missing.getFirst()){case"省份"->"请问考生在哪个省份参加高考？";case"高考年份"->"请问是哪一年的高考？";case"分数或位次"->"请提供高考位次；如果暂时没有，也可以先提供分数。";case"科类/选科"->"请问首选科目是物理还是历史？";default->"目标填报哪个批次？";}; return new Completeness(missing.isEmpty(),percentage,List.copyOf(missing),q);
    }
    private static Integer number(Pattern p,String s){Matcher m=p.matcher(s);return m.find()?Integer.valueOf(m.group(1)):null;}
    private static Integer budget(String s){Matcher m=BUDGET.matcher(s);if(!m.find())return null;double v=Double.parseDouble(m.group(1));return (int)Math.round(v*("万".equals(m.group(2))?10000:1));}
    private static List<String> matches(String text,List<String> candidates){return candidates.stream().filter(text::contains).distinct().toList();}
    private static boolean containsAny(String text,String... values){return Arrays.stream(values).anyMatch(text::contains);}
    private static boolean blank(String s){return s==null||s.isBlank();}
}
