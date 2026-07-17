package com.intelligentresume.ai.material.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "material_resume_generation")
public class MaterialResumeGeneration extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Lob @Column(name = "raw_material_text", nullable = false) private String rawMaterialText;
    @Column(name = "job_description_id") private Long jobDescriptionId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "generated_resume_json", nullable = false, columnDefinition = "json") private Map<String, Object> generatedResumeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "suggestions_json", nullable = false, columnDefinition = "json") private List<String> suggestions;
    public Long getIdValue() { return getId(); }
    public void setUserId(Long value) { userId = value; }
    public void setRawMaterialText(String value) { rawMaterialText = value; }
    public void setJobDescriptionId(Long value) { jobDescriptionId = value; }
    public void setGeneratedResumeJson(Map<String, Object> value) { generatedResumeJson = value; }
    public void setSuggestions(List<String> value) { suggestions = value; }
}
