package com.intelligentresume.careermaterial.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * 职业资料实体，对应数据库表 {@code career_material}，字段与 V1 DDL 完全一致。
 * 一行 = 用户素材库里的一条素材（一段工作经历、一个项目、一条成就……）。
 *
 * <p>链路位置：由 {@code CareerMaterialService} 读写；AI 选材/生成简历时从这里取数据；
 * 简历里的 {@code resume_material_reference} 表保存它的快照副本。
 *
 * <p>改坏它用户会看到什么：丢了 {@code @SQLRestriction} 用户会"看到已删除的素材复活"；
 * 丢了 {@code user_id} 隔离会看到别人的素材；{@code version} 乐观锁失效会让两人同时编辑
 * 时后保存的人悄悄覆盖前一个人（冲突本应报 40901）。
 *
 * <p>软删除:{@code @SQLRestriction("deleted_at IS NULL")} 全局过滤。
 * 被 {@code resume_material_reference.source_snapshot_json} 引用的资料,
 * 删除后历史快照仍可读。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code user_id} 是唯一隔离维度：除按 id 的主键查询外，所有读路径都必须同时带
 *       userId（{@code findByIdAndUserId}），漏掉就是越权漏洞；</li>
 *   <li>软删只写 {@code deleted_at}，不物理删除；也不清空关联，因为引用方保存的是快照副本；</li>
 *   <li>{@code contentJson} 是 json 列，必须走 {@code @JdbcTypeCode(SqlTypes.JSON)}，
 *       换成普通 varchar 映射会导致 ddl-auto=validate 失败；</li>
 *   <li>实体直接参与账号数据导出（#7），所以任何"只给内部用"的投影字段都要加
 *       {@code @JsonIgnore}，否则会漏进导出文件；</li>
 *   <li>{@code version} 由 Hibernate 自增，不要手动 set；并发更新冲突时由
 *       全局异常处理器转 40901。</li>
 * </ul>
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

    /** 展示用标签，不参与"是否有证据"的判定（见 {@code CareerMaterialEvidence}）。 */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 结构化正文：键约定见 {@code CareerMaterialSchema}，单条字节上限由服务层校验。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> contentJson;

    /** 导入/粘贴得到的来源原文，MEDIUMTEXT。列表页不读它，只读非空标志。 */
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
     *
     * <p>{@code @JsonIgnore}：这是搜索用的内部投影，不是实体的对外 JSON 形态
     * （#7 账号数据导出直接序列化实体，不应携带该字段）。
     */
    @JsonIgnore
    @Formula("content_json")
    private String contentJsonText;

    /**
     * 乐观锁版本号（乐观锁 = 不锁行，提交时对比版本号：数据被别人改过就拒绝本次保存）。
     * 有了它，两人同时编辑同一条素材时后提交者会收到 40901 冲突错误，而不是"最后写入覆盖"
     * ——即悄悄把前一个人的修改抹掉（ideation #67）。
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
