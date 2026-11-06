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
 * AI 任务 —— 一次异步 AI 请求在数据库里的唯一"档案行"。
 *
 * <p>【业务问题】AI 调用很慢（几秒到几分钟），不能让 HTTP 请求干等。所以把每一次请求
 * 记成一行数据：用户提交时写入 PENDING，后台工作器领走执行，结果（成功/失败）也写回这一行，
 * 前端凭 taskId 轮询这行数据拿结果。</p>
 *
 * <p>【链路位置】{@code AiTaskService#create} 写入首行 → {@code DatabaseTaskWorker} 经
 * {@code TaskLeaseService} 领取并改状态 → 执行结果经 {@code releaseSuccess/releaseFailed} 写回。
 * 输入快照、生成结果、人工确认状态与跨实例的租约都存在这一行上。</p>
 *
 * <p>【改坏它会怎样】字段与建表 SQL 对不上则应用启动失败；幂等键/指纹逻辑被破坏则
 * 用户重复点提交会创建出重复任务并重复消耗模型额度；租约字段被误写会出现两个实例
 * 重复执行同一任务；时间戳维护被破坏会让保留期清理与配额统计口径错乱。</p>
 *
 * <p>要点 / 红线:</p>
 * <ul>
 *   <li>字段与 V1 DDL {@code ai_task} 逐列对应。改字段前必须先有迁移，否则 Hibernate 校验不过。</li>
 *   <li>{@code (user_id, task_type, idempotency_key)} 上有唯一键。并发同键创建由数据库兜底,
 *       服务层靠捕获唯一键冲突回读赢家({@code AiTaskService#create}),不要在这里加去重逻辑。</li>
 *   <li>{@code lease_owner}/{@code lease_expires_at} 是抢占凭证:只有租约仍属于自己的工作器
 *       才能把任务推到终态,否则该次结果会被丢弃(TaskLeaseService 会校验)。</li>
 *   <li>{@code retry_count} 由原生 SQL 在领取租约时 +1,同时被"每日尝试配额"
 *       ({@code countAttempts...})和"崩溃循环收口"({@code AiTaskWorkerProperties#maxAttempts})使用;
 *       改这个语义会同时改动配额口径与任务终结条件。</li>
 *   <li>{@code input_snapshot_json} 可能内联 JD/资料/简历原文,保留期清理会把它换成
 *       {@code {"_purged": true}} 并置 {@code snapshot_purged};所有读快照的代码必须容忍这个占位值。</li>
 *   <li>不继承 BaseEntity:工作器用原生 SQL 显式写 {@code updated_at},而本类用
 *       {@code @PrePersist}/{@code @PreUpdate} 回调维护时间戳。两条路径并存,不要在 service 再加一层。</li>
 * </ul>
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

    // idempotency_key 与 request_fingerprint 必须成对看:
    // 同 key + 同指纹 = 客户端重试,返回原任务;同 key + 不同指纹 = 内容被改动,抛 CONFLICT。
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

    /** 列为 NOT NULL:创建任务时显式置 0,之后由领取租约的 SQL 自增。 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    /**
     * #26：内联快照与结果是否已按保留期压缩为元数据。
     * 置位后清理作业不再重复处理该行（快照占位 JSON 无 SQL 可判定特征）。
     */
    @Column(name = "snapshot_purged", nullable = false)
    private boolean snapshotPurged;

    // 租约对:领取时由原生 SQL 写入,任务落终态时必须一并清空(Null),
    // 否则后续查询会把已结束的任务当成"仍在执行"。
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
    public boolean isSnapshotPurged() { return snapshotPurged; }
    public void setSnapshotPurged(boolean snapshotPurged) { this.snapshotPurged = snapshotPurged; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
