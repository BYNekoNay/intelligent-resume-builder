package com.intelligentresume.careermaterial.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Formula;
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

    /**
     * content_json 的只读文本投影，供搜索匹配结构化字段值（ideation #2：命中词只在
     * contentJson 中时此前返回错误零结果）。用 {@code @Formula} 而非第二个
     * {@code @Column} 映射：不参与 ddl-auto=validate 的列类型比对（String 映射到
     * MySQL JSON 列会被判类型不符），也不参与写入。匹配含键名（如搜索 "name"），
     * 属 raw 文本匹配的已知取舍；取值文本的命中是修复目标。
     * 依赖 JSON→字符串的隐式转换（lower(content_json)），已在 MySQL 5.7 与测试库
     * H2 上验证。
     */
    @Formula("content_json")
    private String contentJsonText;

    /**
     * 乐观锁版本（ideation #67）：并发编辑不再「最后写入覆盖」，冲突时抛
     * OptimisticLockingFailureException → 全局异常处理器返回 40901。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
    public String getContentJsonText() { return contentJsonText; }
}
