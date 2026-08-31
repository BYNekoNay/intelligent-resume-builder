-- V20: AI 模拟面试完整闭环
-- 保持 MySQL 5.7 兼容
-- H2 MySQL mode 不支持单句 ADD COLUMN 多列,拆为独立语句

-- 1. InterviewSession 扩展字段
ALTER TABLE interview_session ADD COLUMN target_question_count INT NOT NULL DEFAULT 6;
ALTER TABLE interview_session ADD COLUMN min_question_count INT NOT NULL DEFAULT 3;
ALTER TABLE interview_session ADD COLUMN max_question_count INT NOT NULL DEFAULT 9;
ALTER TABLE interview_session ADD COLUMN execution_mode VARCHAR(16) NULL COMMENT 'AI | RULE';
ALTER TABLE interview_session ADD COLUMN completion_reason VARCHAR(64) NULL COMMENT 'AI_INFORMATION_COMPLETE | MAX_QUESTION_LIMIT | TARGET_REACHED_IN_RULE_MODE | USER_FINISHED';

-- current_question 允许 NULL（AI 首题生成中）
ALTER TABLE interview_session MODIFY COLUMN current_question TEXT NULL;

-- 2. InterviewRecord 扩展字段
ALTER TABLE interview_record ADD COLUMN evaluation_source VARCHAR(16) NULL COMMENT 'AI | RULE';
ALTER TABLE interview_record ADD COLUMN ai_attempt_id BIGINT NULL;

-- 3. 新增 interview_ai_attempt 表
CREATE TABLE interview_ai_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL COMMENT 'INITIAL_QUESTION | ANSWER_EVALUATION',
    round_no INT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING | SUCCESS | FAILED | RULE_FALLBACK',
    pending_answer MEDIUMTEXT NULL,
    result_json JSON NULL,
    provider_code VARCHAR(64) NULL,
    model_code VARCHAR(64) NULL,
    provider_request_id VARCHAR(128) NULL,
    prompt_version VARCHAR(32) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    retryable TINYINT(1) NOT NULL DEFAULT 1,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_iai_session FOREIGN KEY (session_id) REFERENCES interview_session(id),
    CONSTRAINT uq_iai_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_iai_session_operation_round UNIQUE (session_id, operation_type, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. 旧状态迁移：IN_PROGRESS → AWAITING_ANSWER
UPDATE interview_session
SET status = 'AWAITING_ANSWER'
WHERE status = 'IN_PROGRESS';

-- 5. 旧数据回填：已有会话和记录标记为 RULE（AI 功能上线前的数据）
UPDATE interview_session
SET execution_mode = 'RULE'
WHERE execution_mode IS NULL;

UPDATE interview_record
SET evaluation_source = 'RULE'
WHERE evaluation_source IS NULL;
