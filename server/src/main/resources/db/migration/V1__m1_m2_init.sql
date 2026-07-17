-- ============================================================
-- V1 — M1/M2 初始化
-- 对应:docs/04 §2.1, §3
-- 字符集:utf8mb4 / utf8mb4_unicode_ci
-- 引擎:InnoDB
-- ============================================================

SET NAMES utf8mb4;

-- 1. user 用户表
CREATE TABLE user (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)     NOT NULL,
    email           VARCHAR(128)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    display_name    VARCHAR(128)    NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3)     NOT NULL,
    updated_at      DATETIME(3)     NOT NULL,
    deleted_at      DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. auth_session 刷新令牌族
CREATE TABLE auth_session (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    token_family_id     CHAR(36)        NOT NULL,
    refresh_token_hash  VARCHAR(255)    NOT NULL,
    issued_at           DATETIME(3)     NOT NULL,
    expires_at          DATETIME(3)     NOT NULL,
    revoked_at          DATETIME(3)     NULL,
    revoke_reason       VARCHAR(64)     NULL,
    user_agent          VARCHAR(255)    NULL,
    ip_address          VARCHAR(64)     NULL,
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_auth_session_user (user_id),
    KEY idx_auth_session_family (token_family_id),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. career_material 职业资料
CREATE TABLE career_material (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    material_type       VARCHAR(32)     NOT NULL,
    title               VARCHAR(255)    NOT NULL,
    content_json        JSON            NOT NULL,
    source_text         MEDIUMTEXT      NULL,
    usage_preference    VARCHAR(16)     NOT NULL DEFAULT 'NORMAL',
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    deleted_at          DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY idx_career_material_user (user_id),
    KEY idx_career_material_user_type (user_id, material_type),
    CONSTRAINT fk_career_material_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. resume 简历主表
CREATE TABLE resume (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    title           VARCHAR(255)    NOT NULL,
    current_version_id BIGINT       NULL,
    created_at      DATETIME(3)     NOT NULL,
    updated_at      DATETIME(3)     NOT NULL,
    deleted_at      DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY idx_resume_user (user_id),
    CONSTRAINT fk_resume_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. resume_version 简历版本
CREATE TABLE resume_version (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    resume_id           BIGINT          NOT NULL,
    version_no          INT             NOT NULL,
    source_type         VARCHAR(32)     NOT NULL,
    resume_json         JSON            NOT NULL,
    optimization_summary VARCHAR(512)   NULL,
    generation_context  JSON            NULL,
    created_by          BIGINT          NOT NULL,
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    deleted_at          DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resume_version_no (resume_id, version_no),
    KEY idx_resume_version_resume (resume_id),
    CONSTRAINT fk_resume_version_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
    CONSTRAINT fk_resume_version_user FOREIGN KEY (created_by) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 把 resume.current_version_id 的外键在所有表创建完成后再加
ALTER TABLE resume ADD CONSTRAINT fk_resume_current_version
    FOREIGN KEY (current_version_id) REFERENCES resume_version(id);

-- 6. job_description 岗位描述
CREATE TABLE job_description (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    title               VARCHAR(255)    NOT NULL,
    company_name        VARCHAR(255)    NULL,
    jd_text             MEDIUMTEXT      NOT NULL,
    parsed_keywords_json JSON           NULL,
    parsed_at           DATETIME(3)     NULL,
    parsed_version      VARCHAR(16)     NULL,
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    deleted_at          DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY idx_job_description_user (user_id),
    CONSTRAINT fk_job_description_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. resume_material_reference 资料引用历史
CREATE TABLE resume_material_reference (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    resume_version_id   BIGINT          NOT NULL,
    material_id         BIGINT          NOT NULL,
    selection_status    VARCHAR(16)     NOT NULL,
    output_path         VARCHAR(255)    NOT NULL,
    source_snapshot_json JSON           NOT NULL,
    created_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_rmr_version (resume_version_id),
    KEY idx_rmr_material (material_id),
    CONSTRAINT fk_rmr_version FOREIGN KEY (resume_version_id) REFERENCES resume_version(id),
    CONSTRAINT fk_rmr_material FOREIGN KEY (material_id) REFERENCES career_material(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. match_result 评分结果
CREATE TABLE match_result (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    resume_version_id   BIGINT          NOT NULL,
    job_description_id  BIGINT          NOT NULL,
    total_score         DECIMAL(5,2)    NOT NULL,
    keyword_score       DECIMAL(5,2)    NOT NULL,
    skill_score         DECIMAL(5,2)    NOT NULL,
    experience_score    DECIMAL(5,2)    NOT NULL,
    explanation_json    JSON            NOT NULL,
    rule_version        VARCHAR(32)     NOT NULL,
    created_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_match_result_version (resume_version_id),
    KEY idx_match_result_jd (job_description_id),
    CONSTRAINT fk_match_result_version FOREIGN KEY (resume_version_id) REFERENCES resume_version(id),
    CONSTRAINT fk_match_result_jd FOREIGN KEY (job_description_id) REFERENCES job_description(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. ai_consent AI 同意事件
CREATE TABLE ai_consent (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    event_type          VARCHAR(16)     NOT NULL,
    policy_version      VARCHAR(32)     NOT NULL,
    provider_code       VARCHAR(64)     NOT NULL,
    task_scopes_json    JSON            NOT NULL,
    data_categories_json JSON           NOT NULL,
    notice_hash         VARCHAR(128)    NOT NULL,
    created_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_consent_user (user_id),
    CONSTRAINT fk_ai_consent_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. ai_task AI 任务
CREATE TABLE ai_task (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT          NOT NULL,
    task_type               VARCHAR(32)     NOT NULL,
    idempotency_key         VARCHAR(128)    NOT NULL,
    request_fingerprint     VARCHAR(128)    NOT NULL,
    input_snapshot_json     JSON            NOT NULL,
    status                  VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    result_json             JSON            NULL,
    error_message           VARCHAR(1024)   NULL,
    confirmation_status     VARCHAR(16)     NULL,
    result_resume_version_id BIGINT         NULL,
    retry_count             INT             NOT NULL DEFAULT 0,
    lease_owner             VARCHAR(64)     NULL,
    lease_expires_at        DATETIME(3)     NULL,
    created_at              DATETIME(3)     NOT NULL,
    updated_at              DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_task_idem (user_id, task_type, idempotency_key),
    KEY idx_ai_task_status (status, lease_expires_at),
    KEY idx_ai_task_user (user_id),
    CONSTRAINT fk_ai_task_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_ai_task_result_version FOREIGN KEY (result_resume_version_id) REFERENCES resume_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. export_task PDF 导出任务
CREATE TABLE export_task (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    resume_version_id   BIGINT          NOT NULL,
    template_code       VARCHAR(32)     NOT NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    storage_key         VARCHAR(64)     NULL,
    file_size_bytes     BIGINT          NULL,
    sha256              VARCHAR(64)     NULL,
    error_message       VARCHAR(1024)   NULL,
    retry_count         INT             NOT NULL DEFAULT 0,
    expires_at          DATETIME(3)     NULL,
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_export_task_user (user_id),
    KEY idx_export_task_status (status),
    CONSTRAINT fk_export_task_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_export_task_version FOREIGN KEY (resume_version_id) REFERENCES resume_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;