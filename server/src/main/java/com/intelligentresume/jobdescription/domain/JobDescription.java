package com.intelligentresume.jobdescription.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 岗位描述。字段与 V1 DDL {@code job_description} 完全一致。
 *
 * <p>软删除:{@code @SQLRestriction("deleted_at IS NULL")} 全局过滤。
 * {@code jd_text} 原文永不覆盖;{@code parsed_keywords_json} 可重复解析覆盖。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code parsed_keywords_json} / {@code parsed_at} / {@code parsed_version} 是
 *       <b>一组</b>：正文变更时三者必须一起清空，只清其中一个会让"有版本号但内容是旧的"
 *       这种脏状态出现；</li>
 *   <li>解析三列都可空——新建的 JD 在用户点"解析"之前没有任何解析结果，
 *       读方必须容忍 null（ScoringService 会在 null 时现场重解析）；</li>
 *   <li>{@code jd_text} 是 MEDIUMTEXT，列表接口只返回截断预览，不要直接把实体全量吐出去；</li>
 *   <li>软删同样只写 {@code deleted_at}；被简历绑定的 JD 删除后，绑定关系本身不会自动解除。</li>
 * </ul>
 */
@Entity
@Table(name = "job_description")
@SQLRestriction("deleted_at IS NULL")
public class JobDescription extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "company_name", length = 255)
    private String companyName;

    /** JD 正文原文，只增不改语义：更新时若内容变化会先清空解析结果。 */
    @Column(name = "jd_text", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String jdText;

    /** 解析结果，形态 {@code {"version": ..., "data": {...}}}；未解析时为 null。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_keywords_json", columnDefinition = "json")
    private Map<String, Object> parsedKeywordsJson;

    /** 解析时间，与 parsedVersion 同生共死，用于展示"上次解析于…"。 */
    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @Column(name = "parsed_version", length = 16)
    private String parsedVersion;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getJdText() { return jdText; }
    public void setJdText(String jdText) { this.jdText = jdText; }
    public Map<String, Object> getParsedKeywordsJson() { return parsedKeywordsJson; }
    public void setParsedKeywordsJson(Map<String, Object> parsedKeywordsJson) { this.parsedKeywordsJson = parsedKeywordsJson; }
    public LocalDateTime getParsedAt() { return parsedAt; }
    public void setParsedAt(LocalDateTime parsedAt) { this.parsedAt = parsedAt; }
    public String getParsedVersion() { return parsedVersion; }
    public void setParsedVersion(String parsedVersion) { this.parsedVersion = parsedVersion; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
