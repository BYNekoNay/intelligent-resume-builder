-- V27: career_material 新增 version（乐观锁，ideation finding #67）
-- 背景：职业资料更新此前是「最后写入覆盖」——两个并发编辑只有一个生效且无任何提示。
-- 引入 Hibernate @Version 后，陈旧写入抛 OptimisticLockingFailureException → 40901。
-- 兼容 MySQL 5.7 / H2 MySQL mode：单条 ALTER 只加一列（参照 V20 教训）。
-- 历史数据由 DEFAULT 0 直接覆盖（与 @Version 初值一致，无需回填）。

ALTER TABLE career_material ADD COLUMN version BIGINT NOT NULL DEFAULT 0;