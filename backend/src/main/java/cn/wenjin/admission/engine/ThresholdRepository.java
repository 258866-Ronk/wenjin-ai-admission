package cn.wenjin.admission.engine;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ThresholdRepository {
    private final JdbcClient jdbc; public ThresholdRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public List<Threshold> find(String province,int year,String subject){
        List<Threshold> exact=query(province,year,subject);
        if(!exact.isEmpty())return exact;
        // 新年份尚未配置分层阈值时，使用不晚于请求年份的最近一套省份阈值。
        return jdbc.sql("select max(admission_year) from province_thresholds where province=:p and admission_year<=:y and subject_type=:s")
                .param("p",province).param("y",year).param("s",subject).query(Integer.class).optional()
                .map(dataYear->query(province,dataYear,subject)).orElseGet(List::of);
    }
    private List<Threshold> query(String province,int year,String subject){return jdbc.sql("select tier,min_rank_gap,max_rank_gap,min_score_gap,max_score_gap,weights_json from province_thresholds where province=:p and admission_year=:y and subject_type=:s").param("p",province).param("y",year).param("s",subject).query((rs,n)->new Threshold(rs.getString(1),rs.getInt(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getString(6))).list();}
    public record Threshold(String tier,int minRankGap,int maxRankGap,int minScoreGap,int maxScoreGap,String weightsJson){}
}
