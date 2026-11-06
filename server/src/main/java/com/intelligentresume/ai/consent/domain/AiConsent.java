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
 * AI 数据处理同意事件 —— 一条授权/撤回流水，字段与 V1 DDL {@code ai_consent} 完全一致。
 *
 * <p>【业务问题】合规要求能回答"用户何时、对哪个版本的协议、授权了哪些数据类别"，
 * 所以每次授权/撤回都记一行流水，而不是一个可被覆盖的开关。</p>
 *
 * <p>【链路位置】只由 {@code AiConsentService} 写入与读取；判定"当前是否已授权"= 最新一行。</p>
 *
 * <p>【改坏它会怎样】这行数据是举证材料，任何修改历史行的行为都会破坏合规性。</p>
 *
 * <p>事件溯源模型:仅追加(append-only),不修改、不软删。
 * 不继承 BaseEntity(无 updated_at)。</p>
 *
 * <p>要点:</p>
 * <ul>
 *   <li>一行 = 一次"授权"或"撤回"事件,不是"当前状态";当前状态由最新一行决定。</li>
 *   <li>{@code task_scopes_json}/{@code data_categories_json} 以 JSON 数组存储,
 *       校验时按列表包含关系使用(见 {@code AiConsentService})。</li>
 *   <li>{@code notice_hash} 是用户当时确认的隐私提示文本摘要,用于举证;不要回填成空值。</li>
 *   <li>无更新路径:实体没有 setter 之外的时间戳维护逻辑,{@code created_at} 只在插入时写一次。</li>
 * </ul>
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
