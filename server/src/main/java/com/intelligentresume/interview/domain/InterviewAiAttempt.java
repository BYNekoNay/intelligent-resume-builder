package com.intelligentresume.interview.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 面试 AI 的一次尝试 —— 幂等与失败重试的载体（ADR-010）。
 *
 * <p>【小白导读】一句话：每次「让 AI 干活」（出首题或点评答案）都先在这里立一张工单，
 * 记下谁发起的、跑到哪一步、成功了还是失败了、能不能重试。它同时承担三件事：
 * 防重复提交（幂等键 + 指纹）、支撑失败重试（代数 + pendingAnswer）、给配额计数。
 *
 * <h2>两个唯一约束各挡一类重复</h2>
 * <ul>
 *   <li>{@code (user_id, idempotency_key)}：同一个请求重复提交只落一条，防止网络重试变成两次计费；</li>
 *   <li>{@code (session_id, operation_type, round_no)}：同一场面试的同一轮同一操作只能有一次尝试，
 *       防止并发点两次「作答」生成两份评估。</li>
 * </ul>
 * 删掉任意一条，重复调用的成本直接翻倍，而且两份结果谁生效取决于写入顺序 —— 排查时几乎无法复现。
 *
 * <h2>两个容易忽略的字段</h2>
 * <ul>
 *   <li>{@code pendingAnswer}：AI 处理期间先把用户答案存下来，进程崩溃也不会丢；</li>
 *   <li>{@code retryable}：区分「稍后重试可能成功」（超时/限流）与「重试也没用」（schema 不合法），
 *       前端据此决定要不要显示重试按钮。</li>
 * </ul>
 *
 * <p>状态推进与陈旧丢弃策略在 {@code InterviewAiService}；本类只负责持久化形态。
 */
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
