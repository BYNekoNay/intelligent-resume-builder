package com.intelligentresume.interview.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "interview_ai_attempt", uniqueConstraints = {
        @UniqueConstraint(name = "uq_iai_user_idempotency", columnNames = {"user_id", "idempotency_key"}),
        @UniqueConstraint(name = "uq_iai_session_operation_round",
                columnNames = {"session_id", "operation_type", "round_no"})
})
public class InterviewAiAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32)
    private AiAttemptOperationType operationType;

    @Column(name = "round_no")
    private Integer roundNo;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 128)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AiAttemptStatus status = AiAttemptStatus.PROCESSING;

    @Column(name = "pending_answer", columnDefinition = "MEDIUMTEXT")
    private String pendingAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "json")
    private Map<String, Object> resultJson;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    @Column(name = "model_code", length = 64)
    private String modelCode;

    @Column(name = "provider_request_id", length = 128)
    private String providerRequestId;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "retryable", nullable = false)
    private Boolean retryable = true;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public AiAttemptOperationType getOperationType() { return operationType; }
    public void setOperationType(AiAttemptOperationType operationType) { this.operationType = operationType; }
    public Integer getRoundNo() { return roundNo; }
    public void setRoundNo(Integer roundNo) { this.roundNo = roundNo; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public AiAttemptStatus getStatus() { return status; }
    public void setStatus(AiAttemptStatus status) { this.status = status; }
    public String getPendingAnswer() { return pendingAnswer; }
    public void setPendingAnswer(String pendingAnswer) { this.pendingAnswer = pendingAnswer; }
    public Map<String, Object> getResultJson() { return resultJson; }
    public void setResultJson(Map<String, Object> resultJson) { this.resultJson = resultJson; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
