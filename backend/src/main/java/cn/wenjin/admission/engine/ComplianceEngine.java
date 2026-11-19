package cn.wenjin.admission.engine;

import cn.wenjin.admission.domain.Domain;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ComplianceEngine {
    private static final List<String>BANNED=List.of("保录","必中","一定能上","百分之百录取","100%录取");
    public Result inspect(String serialized){List<String>issues=new ArrayList<>();for(String word:BANNED)if(serialized.contains(word))issues.add("出现过度承诺词："+word);if(!serialized.contains(Domain.DISCLAIMER))issues.add("缺少完整免责声明");if(serialized.toLowerCase(Locale.ROOT).contains("api_key")||serialized.contains("系统 Prompt"))issues.add("包含内部配置或提示词信息");return new Result(issues.isEmpty(),List.copyOf(issues));}
    public record Result(boolean passed,List<String> issues){}
}
