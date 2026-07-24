package com.intelligentresume.ai.consent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 数据处理同意事件。字段与 V1 DDL {@code ai_consent} 完全一致。
 *
 * <p>事件溯源模型:仅追加(append-only),不修改、不软删。
 * 不继承 BaseEntity(无 updated_at)。
 */
@Entity
@Table(name = "ai_consent")
public class AiConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private ConsentStatus eventType;

    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_scopes_json", nullable = false, columnDefinition = "json")
    private List<String> taskScopesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_categories_json", nullable = false, columnDefinition = "json")
    private List<String> dataCategoriesJson;

    @Column(name = "notice_hash", nullable = false, length = 128)
    private String noticeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public ConsentStatus getEventType() { return eventType; }
    public void setEventType(ConsentStatus eventType) { this.eventType = eventType; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public List<String> getTaskScopesJson() { return taskScopesJson; }
    public void setTaskScopesJson(List<String> taskScopesJson) { this.taskScopesJson = taskScopesJson; }
    public List<String> getDataCategoriesJson() { return dataCategoriesJson; }
    public void setDataCategoriesJson(List<String> dataCategoriesJson) { this.dataCategoriesJson = dataCategoriesJson; }
    public String getNoticeHash() { return noticeHash; }
    public void setNoticeHash(String noticeHash) { this.noticeHash = noticeHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
