-- V35: 账户删除撤销窗口（决策 D2 阶段 3）
-- 删号不再是同步物理删除：请求删号后账号立即停用（DISABLED、会话全撤），但数据保留
-- `cancel_until`（默认 7 天）—— 窗口内可凭密码恢复；窗口结束后由账户清扫作业
-- （AccountPurgeService）级联硬删全部数据并把 user 行删除。
-- 状态流转：PENDING →（撤销）CANCELLED ／ →（作业）RUNNING → SUCCESS / PARTIAL_FAILED（可重入）。
CREATE TABLE account_deletion_job (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    requested_at    DATETIME(3)     NOT NULL,
    cancel_until    DATETIME(3)     NOT NULL,
    completed_at    DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY idx_account_deletion_job_status_until (status, cancel_until),
    KEY idx_account_deletion_job_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
