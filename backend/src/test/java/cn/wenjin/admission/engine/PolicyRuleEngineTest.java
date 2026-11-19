package cn.wenjin.admission.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyRuleEngineTest {
    private final PolicyRuleEngine engine=new PolicyRuleEngine();
    @Test void requiredAndAnySubjectRulesAreHardFilters(){var p=EngineFixtures.profile();var allowed=EngineFixtures.record("a","武汉大学","武汉","计算机",30000,600,"school-official-only",List.of("物理"),List.of("化学","生物"),6000);var blocked=EngineFixtures.record("b","某大学","武汉","医学",30000,600,"school-official-only",List.of("物理","生物"),List.of(),6000);assertThat(engine.allowed(engine.evaluate(p,allowed))).isTrue();assertThat(engine.allowed(engine.evaluate(p,blocked))).isFalse();}
    @Test void budgetOverrunBlocksCandidate(){var result=engine.evaluate(EngineFixtures.profile(),EngineFixtures.record("a","某大学","武汉","计算机",30000,600,"school-official-only",List.of("物理"),List.of(),30000));assertThat(result).anyMatch(x->x.ruleCode().equals("BUDGET")&&!x.passed()&&x.hardRule());}
    @Test void controlLineAndSpecialPathNeverEnterOrdinaryRecommendation(){for(String scope:List.of("control-line-only","special-path-only")){var result=engine.evaluate(EngineFixtures.profile(),EngineFixtures.record(scope,"某大学","武汉","计算机",30000,600,scope,List.of(),List.of(),6000));assertThat(engine.allowed(result)).isFalse();}}
    @Test void historicalYearIsAllowedAndClearlyMarked(){var result=engine.evaluate(EngineFixtures.profile(2026),EngineFixtures.recordForYear(2025,"history","某大学","广州","计算机",30000,600,"school-official-only",List.of(),List.of(),6000));assertThat(engine.allowed(result)).isTrue();assertThat(result).anyMatch(x->x.ruleCode().equals("YEAR")&&x.passed()&&x.message().contains("2025 年历史录取数据参考"));}
    @Test void futureYearRemainsHardBlocked(){var result=engine.evaluate(EngineFixtures.profile(2025),EngineFixtures.recordForYear(2026,"future","某大学","广州","计算机",30000,600,"school-official-only",List.of(),List.of(),6000));assertThat(engine.allowed(result)).isFalse();assertThat(result).anyMatch(x->x.ruleCode().equals("YEAR")&&!x.passed()&&x.hardRule());}
}
