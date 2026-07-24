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

    @Column(name = "jd_text", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String jdText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_keywords_json", columnDefinition = "json")
    private Map<String, Object> parsedKeywordsJson;

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
