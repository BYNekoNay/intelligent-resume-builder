package com.intelligentresume.ai.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 任务。字段与 V1 DDL {@code ai_task} 完全一致。
 *
 * <p>不继承 BaseEntity:工作器通过原生 SQL 手动控制 updated_at,
 * 使用 {@code @PrePersist}/{@code @PreUpdate} 回调管理时间戳。
 */
@Entity
@Table(name = "ai_task")
public class AiTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "parent_task_id")
    private Long parentTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private AiTaskType taskType;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 128)
    private String requestFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> inputSnapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AiTaskStatus status = AiTaskStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "json")
    private Map<String, Object> resultJson;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_status", length = 16)
    private ConfirmationStatus confirmationStatus;

    @Column(name = "result_resume_version_id")
    private Long resultResumeVersionId;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(Long parentTaskId) { this.parentTaskId = parentTaskId; }
    public AiTaskType getTaskType() { return taskType; }
    public void setTaskType(AiTaskType taskType) { this.taskType = taskType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public Map<String, Object> getInputSnapshotJson() { return inputSnapshotJson; }
    public void setInputSnapshotJson(Map<String, Object> inputSnapshotJson) { this.inputSnapshotJson = inputSnapshotJson; }
    public AiTaskStatus getStatus() { return status; }
    public void setStatus(AiTaskStatus status) { this.status = status; }
    public Map<String, Object> getResultJson() { return resultJson; }
    public void setResultJson(Map<String, Object> resultJson) { this.resultJson = resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public ConfirmationStatus getConfirmationStatus() { return confirmationStatus; }
    public void setConfirmationStatus(ConfirmationStatus confirmationStatus) { this.confirmationStatus = confirmationStatus; }
    public Long getResultResumeVersionId() { return resultResumeVersionId; }
    public void setResultResumeVersionId(Long resultResumeVersionId) { this.resultResumeVersionId = resultResumeVersionId; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
