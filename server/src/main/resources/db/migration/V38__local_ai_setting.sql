-- V38: local AI provider settings (non-secret part only).
-- 桌面端允许用户自配 AI 的 endpoint / model（R31：可增、测、改、禁、删）。
-- ⚠ 密钥**不在这张表里**：ADR-012 D8 要求凭据用 DPAPI 加密存在数据目录下的独立文件，
-- 且 R34 明确「凭据不得进备份 / Web 导出包 / 桌面迁移包」。备份是整库 SCRIPT TO，
-- 所以密钥一旦入库就必然被打包带走 —— 这也是本表刻意没有 credential 列的原因。
-- credential_present 只是「本机是否已加密保存过密钥」的标记，用于前端提示重新配置。
CREATE TABLE local_ai_setting (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    provider_code       VARCHAR(32)     NOT NULL,
    base_url            VARCHAR(512)    NOT NULL,
    model               VARCHAR(128)    NOT NULL,
    enabled             TINYINT         NOT NULL DEFAULT 1,
    credential_present  TINYINT         NOT NULL DEFAULT 0,
    last_tested_at      DATETIME(3)     NULL,
    last_test_ok        TINYINT         NULL,
    last_test_message   VARCHAR(512)    NULL,
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_ai_setting_user (user_id)
);
