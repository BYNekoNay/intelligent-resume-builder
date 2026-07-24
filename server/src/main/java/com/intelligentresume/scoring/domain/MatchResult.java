package com.intelligentresume.scoring.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 匹配评分结果。字段与 V1 DDL {@code match_result} 完全一致。
 *
 * <p>不继承 BaseEntity：DDL 中无 {@code updated_at} 列，
 * 使用 {@code @PrePersist} 管理 {@code created_at}。
 * 无 {@code user_id} 列——跨用户校验通过 resume_version → resume → user_id 间接完成。
 */
@Entity
@Table(name = "match_result")
public class MatchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;

    @Column(name = "job_description_id", nullable = false)
    private Long jobDescriptionId;

    @Column(name = "total_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "keyword_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal keywordScore;

    @Column(name = "skill_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal skillScore;

    @Column(name = "experience_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal experienceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> explanationJson;

    @Column(name = "rule_version", nullable = false, length = 32)
    private String ruleVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
    public BigDecimal getKeywordScore() { return keywordScore; }
    public void setKeywordScore(BigDecimal keywordScore) { this.keywordScore = keywordScore; }
    public BigDecimal getSkillScore() { return skillScore; }
    public void setSkillScore(BigDecimal skillScore) { this.skillScore = skillScore; }
    public BigDecimal getExperienceScore() { return experienceScore; }
    public void setExperienceScore(BigDecimal experienceScore) { this.experienceScore = experienceScore; }
    public Map<String, Object> getExplanationJson() { return explanationJson; }
    public void setExplanationJson(Map<String, Object> explanationJson) { this.explanationJson = explanationJson; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
