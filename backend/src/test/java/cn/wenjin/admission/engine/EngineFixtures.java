package cn.wenjin.admission.engine;

import cn.wenjin.admission.domain.Domain.AdmissionRecord;
import cn.wenjin.admission.domain.Domain.ProfilePatch;
import cn.wenjin.admission.domain.Domain.StudentProfile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class EngineFixtures {
    private EngineFixtures() { }

    static StudentProfile profile() {
        return profile(2025);
    }

    static StudentProfile profile(int examYear) {
        return StudentProfile.empty("p", "u").withUpdates(new ProfilePatch("河南", examYear, 590, 28000,
                "物理类", "物理", List.of("化学"), "本科批", "普通类", 25000,
                List.of("武汉"), List.of(), List.of("计算机"), List.of(), List.of(), null,
                false, false, false, List.of(), "BALANCED", null), "m");
    }

    static AdmissionRecord record(String id, String school, String city, String major, Integer rank,
                                  Integer score, String scope, List<String> required, List<String> any,
                                  Integer tuition) {
        return recordForYear(2025, id, school, city, major, rank, score, scope, required, any, tuition);
    }

    static AdmissionRecord recordForYear(int year, String id, String school, String city, String major,
                                         Integer rank, Integer score, String scope, List<String> required,
                                         List<String> any, Integer tuition) {
        return new AdmissionRecord(id, id, "demo-2025-v1", "河南", year, "物理类", "本科批",
                school, "100", List.of("公办"), city, "MAJOR_ADMISSION", major, "01", "01",
                String.join("、", required), required, any, score,
                BigDecimal.valueOf(score == null ? 0 : score), score, rank, rank, 2, tuition, scope,
                rank == null, rank == null, "普通录取", "普通类", List.of(), "source-1",
                "https://example.edu", "official-school", Instant.now(), List.of());
    }
}
