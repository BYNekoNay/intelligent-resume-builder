package com.intelligentresume.ai.confirmation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 简历版本与职业资料的引用关系。字段与 V1 DDL {@code resume_material_reference}
 * 及 V8 新增的 {@code selection_reason} 列完全一致。
 *
 * <p>不继承 BaseEntity：DDL 中无 {@code updated_at} 列，
 * 使用 {@code @PrePersist} 管理 {@code created_at}。
 *
 * <p>{@code source_snapshot_json} 在 commit 时复制 CareerMaterial 全部字段，
 * 即使资料后续被软删，历史快照仍可读。
 */
@Entity
@Table(name = "resume_material_reference")
public class ResumeMaterialReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "selection_status", nullable = false, length = 16)
    private String selectionStatus;

    @Column(name = "output_path", nullable = false, length = 255)
    private String outputPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_snapshot_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> sourceSnapshotJson;

    @Column(name = "selection_reason", length = 500)
    private String selectionReason;

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
    public Long getMaterialId() { return materialId; }
    public void setMaterialId(Long materialId) { this.materialId = materialId; }
    public String getSelectionStatus() { return selectionStatus; }
    public void setSelectionStatus(String selectionStatus) { this.selectionStatus = selectionStatus; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public Map<String, Object> getSourceSnapshotJson() { return sourceSnapshotJson; }
    public void setSourceSnapshotJson(Map<String, Object> sourceSnapshotJson) { this.sourceSnapshotJson = sourceSnapshotJson; }
    public String getSelectionReason() { return selectionReason; }
    public void setSelectionReason(String selectionReason) { this.selectionReason = selectionReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
