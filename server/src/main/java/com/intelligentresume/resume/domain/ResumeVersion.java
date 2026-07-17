package com.intelligentresume.resume.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "resume_version")
public class ResumeVersion extends BaseEntity {

    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resume_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> resumeJson;

    @Column(name = "optimization_summary", length = 512)
    private String optimizationSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generation_context", columnDefinition = "json")
    private Map<String, Object> generationContext;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    public enum SourceType {
        MANUAL,
        AI_OPTIMIZED,
        JD_CUSTOMIZED,
        MATERIAL_CUSTOMIZED
    }

    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long resumeId) { this.resumeId = resumeId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }
    public Map<String, Object> getResumeJson() { return resumeJson; }
    public void setResumeJson(Map<String, Object> resumeJson) { this.resumeJson = resumeJson; }
    public String getOptimizationSummary() { return optimizationSummary; }
    public void setOptimizationSummary(String optimizationSummary) { this.optimizationSummary = optimizationSummary; }
    public Map<String, Object> getGenerationContext() { return generationContext; }
    public void setGenerationContext(Map<String, Object> generationContext) { this.generationContext = generationContext; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}