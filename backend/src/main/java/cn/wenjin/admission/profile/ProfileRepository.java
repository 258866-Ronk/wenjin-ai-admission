package cn.wenjin.admission.profile;

import cn.wenjin.admission.common.Jsons;
import cn.wenjin.admission.domain.Domain.FieldValue;
import cn.wenjin.admission.domain.Domain.StudentProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProfileRepository {
    private final JdbcClient jdbc;
    public ProfileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public StudentProfile create(StudentProfile p) {
        jdbc.sql("""
                insert into student_profiles(id,user_id,province,exam_year,score,rank_value,rank_source,subject_type,primary_subject,
                secondary_subjects_json,batch,candidate_category,annual_tuition_budget,preferred_cities_json,excluded_cities_json,
                preferred_majors_json,excluded_majors_json,school_level_preferences_json,employment_direction,accept_private,
                accept_cooperative,accept_vocational,medical_restrictions_json,risk_preference,special_requirements,field_meta_json,
                version,created_at,updated_at) values (:id,:userId,:province,:year,:score,:rank,:rankSource,:subjectType,:primarySubject,
                :secondary,:batch,:category,:budget,:cities,:excludedCities,:majors,:excludedMajors,:schoolLevels,:employment,
                :acceptPrivate,:acceptCooperative,:acceptVocational,:medical,:risk,:special,:meta,:version,:now,:now)
                """).params(params(p)).update();
        return p;
    }

    public Optional<StudentProfile> findByUserId(String userId) {
        return jdbc.sql("select * from student_profiles where user_id=:userId and deleted_at is null")
                .param("userId", userId).query(this::map).optional();
    }

    public StudentProfile save(StudentProfile p) {
        Map<String, Object> params = params(p);
        jdbc.sql("""
                update student_profiles set province=:province,exam_year=:year,score=:score,rank_value=:rank,rank_source=:rankSource,
                subject_type=:subjectType,primary_subject=:primarySubject,secondary_subjects_json=:secondary,batch=:batch,
                candidate_category=:category,annual_tuition_budget=:budget,preferred_cities_json=:cities,
                excluded_cities_json=:excludedCities,preferred_majors_json=:majors,excluded_majors_json=:excludedMajors,
                school_level_preferences_json=:schoolLevels,employment_direction=:employment,accept_private=:acceptPrivate,
                accept_cooperative=:acceptCooperative,accept_vocational=:acceptVocational,medical_restrictions_json=:medical,
                risk_preference=:risk,special_requirements=:special,field_meta_json=:meta,version=:version,updated_at=:now where id=:id
                """).params(params).update();
        return p;
    }

    private Map<String, Object> params(StudentProfile p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", p.id()); values.put("userId", p.userId()); values.put("province", p.province());
        values.put("year", p.examYear()); values.put("score", p.score()); values.put("rank", p.rank());
        values.put("rankSource", p.rankSource()); values.put("subjectType", p.subjectType()); values.put("primarySubject", p.primarySubject());
        values.put("secondary", Jsons.write(p.secondarySubjects())); values.put("batch", p.batch()); values.put("category", p.candidateCategory());
        values.put("budget", p.annualTuitionBudget()); values.put("cities", Jsons.write(p.preferredCities()));
        values.put("excludedCities", Jsons.write(p.excludedCities())); values.put("majors", Jsons.write(p.preferredMajors()));
        values.put("excludedMajors", Jsons.write(p.excludedMajors())); values.put("schoolLevels", Jsons.write(p.schoolLevelPreferences()));
        values.put("employment", p.employmentDirection()); values.put("acceptPrivate", p.acceptPrivate());
        values.put("acceptCooperative", p.acceptCooperative()); values.put("acceptVocational", p.acceptVocational());
        values.put("medical", Jsons.write(p.medicalRestrictions())); values.put("risk", p.riskPreference());
        values.put("special", p.specialRequirements()); values.put("meta", Jsons.write(p.fieldMeta()));
        values.put("version", p.version()); values.put("now", java.sql.Timestamp.from(Instant.now()));
        return values;
    }

    @SuppressWarnings("unchecked")
    private StudentProfile map(ResultSet rs, int rowNum) throws SQLException {
        Map<String, FieldValue> meta = new LinkedHashMap<>();
        Jsons.objectMap(rs.getString("field_meta_json")).forEach((key, value) -> {
            if (value instanceof Map<?, ?> m) {
                Object confidence = m.get("confidence");
                Object updatedAt = m.get("updatedAt");
                meta.put(key, new FieldValue(m.get("value"), confidence instanceof Number n ? n.doubleValue() : 0,
                        String.valueOf(m.get("sourceMessageId")), updatedAt == null ? null : Instant.parse(String.valueOf(updatedAt))));
            }
        });
        return new StudentProfile(rs.getString("id"), rs.getString("user_id"), rs.getString("province"),
                nullableInt(rs, "exam_year"), nullableInt(rs, "score"), nullableInt(rs, "rank_value"),
                rs.getString("rank_source"), rs.getString("subject_type"), rs.getString("primary_subject"),
                Jsons.stringList(rs.getString("secondary_subjects_json")), rs.getString("batch"),
                rs.getString("candidate_category"), nullableInt(rs, "annual_tuition_budget"),
                Jsons.stringList(rs.getString("preferred_cities_json")), Jsons.stringList(rs.getString("excluded_cities_json")),
                Jsons.stringList(rs.getString("preferred_majors_json")), Jsons.stringList(rs.getString("excluded_majors_json")),
                Jsons.stringList(rs.getString("school_level_preferences_json")), rs.getString("employment_direction"),
                nullableBoolean(rs, "accept_private"), nullableBoolean(rs, "accept_cooperative"),
                nullableBoolean(rs, "accept_vocational"), Jsons.stringList(rs.getString("medical_restrictions_json")),
                rs.getString("risk_preference"), rs.getString("special_requirements"), meta, rs.getInt("version"),
                rs.getTimestamp("updated_at").toInstant());
    }
    private static Integer nullableInt(ResultSet rs, String col) throws SQLException { int v=rs.getInt(col); return rs.wasNull()?null:v; }
    private static Boolean nullableBoolean(ResultSet rs, String col) throws SQLException { boolean v=rs.getBoolean(col); return rs.wasNull()?null:v; }
}
