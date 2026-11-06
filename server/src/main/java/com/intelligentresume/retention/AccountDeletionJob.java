package com.intelligentresume.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 账户删除撤销窗口任务（决策 D2 阶段 3，V35）。一行 = 一次删号申请：
 * 记录谁申请删号、什么时候到反悔截止（cancel_until）、现在处理到哪一步（status）。
 *
 * <p>链路位置：用户删号 → {@code deleteAccount} 落一行 PENDING → 7 天窗口内可恢复 →
 * 窗口过后 {@link AccountPurgeService} 扫到它执行真删 → 行转 SUCCESS。
 *
 * <p>删号请求 → {@code deleteAccount} 立即停用账号并撤销全部会话（安全动作不可逆），
 * 同时落一行 {@code PENDING}；窗口（{@code cancel_until}）内用户可凭密码恢复
 * （{@code AuthService.restoreDeletion} → 行转 {@code CANCELLED}）；窗口结束后由
 * {@link AccountPurgeService} 级联硬删全部数据 → 行转 {@code SUCCESS}。
 *
 * <p>同一用户同时最多一行 {@code PENDING}（{@code deleteAccount} 幂等保护）；
 * 撤销后再次删号会插入新行（历史 CANCELLED/SUCCESS 行留作审计）。
 */
@Entity
@Table(name = "account_deletion_job")
public class AccountDeletionJob {

    public enum Status { PENDING, RUNNING, PARTIAL_FAILED, SUCCESS, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 必须显式 STRING：默认 ORDINAL 会把状态写成 0/1/…，与 V35 的 VARCHAR('PENDING'…) 永远对不上
    // （第 74 批实测：缺注解时派生查询 StatusIn 恒返回空，清扫静默不处理任何任务）。
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "cancel_until", nullable = false)
    private LocalDateTime cancelUntil;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getCancelUntil() { return cancelUntil; }
    public void setCancelUntil(LocalDateTime cancelUntil) { this.cancelUntil = cancelUntil; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
