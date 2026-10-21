-- V37: desktop workspace import mapping.
-- 桌面端从 Web 迁移包导入时，需要记住「本地这条记录 ← 来源包的哪条稳定 ID」。
-- 没有这张表，重复导入同一份包会被当成全新数据重复写入（违反 R29「重复导入的相同记录应被跳过」）。
-- 稳定 ID 形如 web:resume:123 / web:resume:123:versions:456，由导出侧（AccountExportService）生成。
CREATE TABLE desktop_import_map (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    entity_type         VARCHAR(64)     NOT NULL,
    local_id            BIGINT          NOT NULL,
    source_stable_id    VARCHAR(191)    NOT NULL,
    imported_at         DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_desktop_import_map_source (source_stable_id),
    KEY idx_desktop_import_map_local (entity_type, local_id),
    KEY idx_desktop_import_map_user (user_id)
);
