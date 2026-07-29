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
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;
    @Column(name = "job_description_id", nullable = false)
    private Long jobDescriptionId;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 128)
    private String requestFingerprint;
    @Column(name = "total_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalScore;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> resultJson;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
    public Map<String, Object> getResultJson() { return resultJson; }
    public void setResultJson(Map<String, Object> resultJson) { this.resultJson = resultJson; }
}
