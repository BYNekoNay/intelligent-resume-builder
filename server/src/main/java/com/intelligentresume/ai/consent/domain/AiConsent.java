package com.intelligentresume.ai.consent.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "ai_consent")
public class AiConsent extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private ConsentEventType eventType;

    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_scopes_json", nullable = false, columnDefinition = "json")
    private List<String> taskScopes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_categories_json", nullable = false, columnDefinition = "json")
    private List<String> dataCategories;

    @Column(name = "notice_hash", nullable = false, length = 128)
    private String noticeHash;

    public enum ConsentEventType {
        GRANTED,
        WITHDRAWN
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public ConsentEventType getEventType() { return eventType; }
    public void setEventType(ConsentEventType eventType) { this.eventType = eventType; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public List<String> getTaskScopes() { return taskScopes; }
    public void setTaskScopes(List<String> taskScopes) { this.taskScopes = taskScopes; }
    public List<String> getDataCategories() { return dataCategories; }
    public void setDataCategories(List<String> dataCategories) { this.dataCategories = dataCategories; }
    public String getNoticeHash() { return noticeHash; }
    public void setNoticeHash(String noticeHash) { this.noticeHash = noticeHash; }
}