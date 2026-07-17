package com.intelligentresume.jobdescription.domain;

import com.intelligentresume.common.persistence.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "job_description")
public class JobDescription extends SoftDeletableEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Lob
    @Column(name = "jd_text", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String jdText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_keywords_json", columnDefinition = "json")
    private Map<String, Object> parsedKeywordsJson;

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @Column(name = "parsed_version", length = 16)
    private String parsedVersion;

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
}