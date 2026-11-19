package cn.wenjin.admission.profile;

import cn.wenjin.admission.domain.Domain.StudentProfile;
import org.junit.jupiter.api.Test;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProfileServiceTest {
    private final ProfileService service = new ProfileService(mock(ProfileRepository.class));

    @Test void extractsDemoProfileWithoutInventingUnmentionedFields() {
        var patch = service.extract("我是河南考生，2025年高考，物理方向，考了590分，位次28000，想学计算机，最好去武汉、杭州或南京，家庭一年学费预算25000元，不接受中外合作。");
        assertThat(patch.province()).isEqualTo("河南");
        assertThat(patch.examYear()).isEqualTo(2025);
        assertThat(patch.score()).isEqualTo(590);
        assertThat(patch.rank()).isEqualTo(28000);
        assertThat(patch.primarySubject()).isEqualTo("物理");
        assertThat(patch.preferredCities()).containsExactly("武汉", "杭州", "南京");
        assertThat(patch.acceptCooperative()).isFalse();
        assertThat(patch.acceptPrivate()).isNull();
    }

    @Test void completenessAsksOnlyOneHighestPriorityQuestion() {
        var result = service.completeness(StudentProfile.empty("p", "u"));
        assertThat(result.complete()).isFalse();
        assertThat(result.nextQuestion()).contains("省份");
        assertThat(result.missingFields()).hasSize(5);
    }

    @Test void acceptsCurrentYearAndStandaloneFourDigitYear() {
        assertThat(service.extract("今年参加高考").examYear()).isEqualTo(Year.now().getValue());
        assertThat(service.extract("2026").examYear()).isEqualTo(2026);
    }

    @Test void changingExamContextDropsTheOldProvinceRank() {
        var original = StudentProfile.empty("p", "u").withUpdates(service.extract(
                "我是广东考生，2025年高考，物理类，考了600分，位次28000。"), "m1");
        var changed = original.withUpdates(service.extract(
                "我是福建历史类考生，今年考了550分，想学法学。"), "m2");
        assertThat(changed.province()).isEqualTo("福建");
        assertThat(changed.examYear()).isEqualTo(Year.now().getValue());
        assertThat(changed.score()).isEqualTo(550);
        assertThat(changed.rank()).isNull();
        assertThat(changed.preferredMajors()).containsExactly("法学");
    }
}
