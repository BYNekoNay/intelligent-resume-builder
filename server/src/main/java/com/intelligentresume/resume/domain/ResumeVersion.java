package com.intelligentresume.resume.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 简历版本。字段与 V1 DDL {@code resume_version} 完全一致。
 *
 * <p>历史版本不可修改（本卡不提供 update 接口）。
 * 版本号 {@code (resume_id, version_no)} 由数据库唯一约束保障。
 */
@Entity
@Table(name = "resume_version")
public class ResumeVersion extends BaseEntity {

    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private ResumeSourceType sourceType;

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

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "restored_from_version_id")
    private Long restoredFromVersionId;

    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long resumeId) { this.resumeId = resumeId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public ResumeSourceType getSourceType() { return sourceType; }
    public void setSourceType(ResumeSourceType sourceType) { this.sourceType = sourceType; }
    public Map<String, Object> getResumeJson() { return resumeJson; }
    public void setResumeJson(Map<String, Object> resumeJson) { this.resumeJson = resumeJson; }
    public String getOptimizationSummary() { return optimizationSummary; }
    public void setOptimizationSummary(String optimizationSummary) { this.optimizationSummary = optimizationSummary; }
    public Map<String, Object> getGenerationContext() { return generationContext; }
    public void setGenerationContext(Map<String, Object> generationContext) { this.generationContext = generationContext; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public Long getRestoredFromVersionId() { return restoredFromVersionId; }
    public void setRestoredFromVersionId(Long restoredFromVersionId) { this.restoredFromVersionId = restoredFromVersionId; }
}
