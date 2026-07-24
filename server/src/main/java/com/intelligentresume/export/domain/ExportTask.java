package com.intelligentresume.export.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * PDF 导出任务实体。字段与 V1 DDL {@code export_task} 完全一致。
 *
 * <p>DDL 含 user_id 列,跨用户校验直接 findByIdAndUserId。
 * storage_key 为随机 UUID,不含任何业务字段。
 */
@Entity
@Table(name = "export_task")
public class ExportTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;

    @Column(name = "template_code", nullable = false, length = 32)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ExportStatus status = ExportStatus.PENDING;

    @Column(name = "storage_key", length = 64)
    private String storageKey;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }

    public ExportStatus getStatus() { return status; }
    public void setStatus(ExportStatus status) { this.status = status; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
