package com.intelligentresume.ats.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

@Entity
@Table(name = "ats_check_result")
public class AtsCheckResult extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "resume_version_id", nullable = false) private Long resumeVersionId;
    @Column(name = "job_description_id", nullable = false) private Long jobDescriptionId;
    @Column(name = "total_score", nullable = false, precision = 5, scale = 2) private BigDecimal totalScore;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "json") private Map<String, Object> resultJson;

    public void setUserId(Long value) { userId = value; }
    public void setResumeVersionId(Long value) { resumeVersionId = value; }
    public void setJobDescriptionId(Long value) { jobDescriptionId = value; }
    public void setTotalScore(BigDecimal value) { totalScore = value; }
    public void setResultJson(Map<String, Object> value) { resultJson = value; }
}
