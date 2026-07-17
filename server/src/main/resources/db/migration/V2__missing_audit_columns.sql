-- ============================================================
-- V2 — 缺失审计列补全
--
-- 一些表的 V1 DDL 不含 created_at / updated_at,
-- 但对应的 JPA Entity 继承自 BaseEntity,后者强制要求。
-- 通过 V2 补列,保持 V1 不动。
-- ============================================================

ALTER TABLE ai_consent ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE match_result ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE resume_material_reference ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
