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
 * <p>职责一句话：一条版本 = 某个时刻的完整简历内容快照（含版式配置），只增不改。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code source_type} 记录版本从哪来（手工/AI 优化/JD 定制/素材定制/恢复），是审计入口。</li>
 *     <li>{@code deleted_at} 在这里表示**归档**而非删除：行还在，只是不出现在活跃列表。</li>
 *     <li>{@code template_code} 是 {@code resume_json.template.code} 的归一化派生列，
 *         写入时必须与 {@code ResumeTemplateCodes.normalize} 保持一致，不能各算各的。</li>
 *     <li>{@code generation_context} 只在特定来源（如 ATS 恢复）写入，可为空。</li>
 * </ul>
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

    /**
     * #50：列表投影用的派生列（{@code resume_json.template.code} 归一化结果）。
     * 历史行（V32 之前）为 NULL，由列表读路径惰性回填；新建版本在写入时即派生。
     */
    @Column(name = "template_code", length = 32)
    private String templateCode;

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
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public Map<String, Object> getGenerationContext() { return generationContext; }
    public void setGenerationContext(Map<String, Object> generationContext) { this.generationContext = generationContext; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public Long getRestoredFromVersionId() { return restoredFromVersionId; }
    public void setRestoredFromVersionId(Long restoredFromVersionId) { this.restoredFromVersionId = restoredFromVersionId; }
}
