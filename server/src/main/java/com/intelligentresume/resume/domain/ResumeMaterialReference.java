package com.intelligentresume.resume.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.intelligentresume.common.persistence.BaseEntity;

import java.util.Map;

@Entity
@Table(name = "resume_material_reference")
public class ResumeMaterialReference extends BaseEntity {
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

    public Long getResumeVersionId() { return resumeVersionId; }
    public Long getMaterialId() { return materialId; }
    public String getSelectionStatus() { return selectionStatus; }
    public String getOutputPath() { return outputPath; }
    public Map<String, Object> getSourceSnapshotJson() { return sourceSnapshotJson; }
    public String getSelectionReason() { return selectionReason; }
    public void setResumeVersionId(Long value) { resumeVersionId = value; }
    public void setMaterialId(Long value) { materialId = value; }
    public void setSelectionStatus(String value) { selectionStatus = value; }
    public void setOutputPath(String value) { outputPath = value; }
    public void setSourceSnapshotJson(Map<String, Object> value) { sourceSnapshotJson = value; }
    public void setSelectionReason(String value) { selectionReason = value; }
}
