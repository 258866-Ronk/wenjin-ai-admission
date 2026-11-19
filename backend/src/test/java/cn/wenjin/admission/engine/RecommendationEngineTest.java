package cn.wenjin.admission.engine;

import cn.wenjin.admission.engine.ThresholdRepository.Threshold;
import org.junit.jupiter.api.BeforeEach;import org.junit.jupiter.api.Test;import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;import static org.mockito.Mockito.*;

class RecommendationEngineTest {
    private RecommendationEngine engine;
    @BeforeEach void setup(){ThresholdRepository repo=mock(ThresholdRepository.class);when(repo.find(anyString(),anyInt(),anyString())).thenReturn(List.of(new Threshold("高冲",3501,Integer.MAX_VALUE,Integer.MIN_VALUE,-9,"{}"),new Threshold("普通冲",601,3500,-8,-1,"{}"),new Threshold("偏稳",-1499,600,0,7,"{}"),new Threshold("稳妥",-4999,-1500,8,17,"{}"),new Threshold("保底",Integer.MIN_VALUE,-5000,18,Integer.MAX_VALUE,"{}")));engine=new RecommendationEngine(repo,new PolicyRuleEngine());}
    @Test void rankBoundaryUsesDatabaseThresholds(){var result=engine.recommend(EngineFixtures.profile(),List.of(EngineFixtures.record("a","A大学","武汉","计算机",27400,590,"school-official-only",List.of(),List.of(),6000),EngineFixtures.record("b","B大学","武汉","计算机",33000,590,"school-official-only",List.of(),List.of(),6000)));assertThat(result.candidates()).extracting(x->x.tier()).contains("偏稳","保底");}
    @Test void majorAndCityPreferencesIncreaseScore(){var result=engine.recommend(EngineFixtures.profile(),List.of(EngineFixtures.record("a","A大学","武汉","计算机科学",33000,590,"school-official-only",List.of(),List.of(),6000),EngineFixtures.record("b","B大学","外地","材料工程",33000,590,"school-official-only",List.of(),List.of(),6000)));assertThat(result.candidates().get(0).record().id()).isEqualTo("a");assertThat(result.candidates().get(0).scoreBreakdown().majorPreference()).isGreaterThan(result.candidates().get(1).scoreBreakdown().majorPreference());}
    @Test void missingRankStaysMissingAndFallsBackToScoreBand(){var record=EngineFixtures.record("a","A大学","杭州","计算机",null,610,"school-official-only",List.of(),List.of(),6000);var candidate=engine.recommend(EngineFixtures.profile(),List.of(record)).candidates().getFirst();assertThat(candidate.record().minRankEnd()).isNull();assertThat(candidate.record().rankUnavailable()).isTrue();assertThat(candidate.riskNotes()).anyMatch(x->x.contains("未公开最低位次"));}
}

