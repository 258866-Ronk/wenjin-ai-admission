package cn.wenjin.admission.engine;

import cn.wenjin.admission.domain.Domain;import cn.wenjin.admission.domain.Domain.TierSummary;import org.junit.jupiter.api.Test;import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class RiskAndComplianceEngineTest {
 @Test void insufficientSafeCandidatesAreHighRisk(){var risks=new RiskEngine().check(EngineFixtures.profile(),List.of(),new TierSummary(0,0,0));assertThat(risks).anyMatch(x->x.code().equals("INSUFFICIENT_SAFE")&&x.level()==Domain.RiskLevel.HIGH);assertThat(risks).anyMatch(x->x.blocking());}
 @Test void complianceRejectsPromisesAndRequiresDisclaimer(){ComplianceEngine engine=new ComplianceEngine();assertThat(engine.inspect("本方案必中").passed()).isFalse();assertThat(engine.inspect("辅助分析。"+Domain.DISCLAIMER).passed()).isTrue();}
}

