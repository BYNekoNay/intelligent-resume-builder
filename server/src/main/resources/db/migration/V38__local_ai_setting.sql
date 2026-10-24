-- V38: local AI provider settings (non-secret part only).
-- 桌面端允许用户自配 AI 的 endpoint / model（R31：可增、测、改、禁、删）。
-- ⚠ 密钥**不在这张表里**：ADR-012 D8 要求凭据用 DPAPI 加密存在数据目录下的独立文件，
-- 且 R34 明确「凭据不得进备份 / Web 导出包 / 桌面迁移包」。备份是整库 SCRIPT TO，
-- 所以密钥一旦入库就必然被打包带走 —— 这也是本表刻意没有 credential 列的原因。
-- credential_present 只是「本机是否已加密保存过密钥」的标记，用于前端提示重新配置。
-- 布尔列必须写 BOOLEAN 而不是 TINYINT：MySQL 驱动只在 TINYINT(1) 时才映射成 BIT，
-- 而 Hibernate 的 schema 校验按 Boolean 类型比对列类型 —— 写 TINYINT 会让 ci/prod 启动直接失败
-- （2026-10-24 由 CI 的 functional job 实测抓到，本地 H2 路径校验不到）。
CREATE TABLE local_ai_setting (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    provider_code       VARCHAR(32)     NOT NULL,
    base_url            VARCHAR(512)    NOT NULL,
    model               VARCHAR(128)    NOT NULL,
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    credential_present  BOOLEAN         NOT NULL DEFAULT FALSE,
    last_tested_at      DATETIME(3)     NULL,
    last_test_ok        BOOLEAN         NULL,
    last_test_message   VARCHAR(512)    NULL,
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_ai_setting_user (user_id)
);
