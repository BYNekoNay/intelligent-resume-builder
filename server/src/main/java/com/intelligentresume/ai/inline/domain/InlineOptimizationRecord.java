package com.intelligentresume.ai.inline.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "inline_optimization_record")
public class InlineOptimizationRecord extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;

    @Column(name = "job_description_id")
    private Long jobDescriptionId;

    @Column(name = "section_code", nullable = false, length = 64)
    private String sectionCode;

    @Lob
    @Column(name = "original_content", nullable = false)
    private String originalContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> resultJson;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    public void setUserId(Long userId) { this.userId = userId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
    public void setSectionCode(String sectionCode) { this.sectionCode = sectionCode; }
    public void setOriginalContent(String originalContent) { this.originalContent = originalContent; }
    public void setResultJson(Map<String, Object> resultJson) { this.resultJson = resultJson; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
}
