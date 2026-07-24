package com.intelligentresume.careermaterial.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 职业资料。字段与 V1 DDL {@code career_material} 完全一致。
 *
 * <p>软删除:{@code @SQLRestriction("deleted_at IS NULL")} 全局过滤。
 * 被 {@code resume_material_reference.source_snapshot_json} 引用的资料,
 * 删除后历史快照仍可读。
 */
@Entity
@Table(name = "career_material")
@SQLRestriction("deleted_at IS NULL")
public class CareerMaterial extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 32)
    private MaterialType materialType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> contentJson;

    @Column(name = "source_text", columnDefinition = "MEDIUMTEXT")
    private String sourceText;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_preference", nullable = false, length = 16)
    private UsagePreference usagePreference = UsagePreference.NORMAL;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public MaterialType getMaterialType() { return materialType; }
    public void setMaterialType(MaterialType materialType) { this.materialType = materialType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Map<String, Object> getContentJson() { return contentJson; }
    public void setContentJson(Map<String, Object> contentJson) { this.contentJson = contentJson; }
    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }
    public UsagePreference getUsagePreference() { return usagePreference; }
    public void setUsagePreference(UsagePreference usagePreference) { this.usagePreference = usagePreference; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
