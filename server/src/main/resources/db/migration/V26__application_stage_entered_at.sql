-- V26: application_record 新增 stage_entered_at（当前状态进入时刻）
-- 修复模块核实报告 P1-4：投递看板 avgStageDurationDays.interviewing 原用 updated_at
-- 充当"进入面试时间"，但 updated_at 在任何字段更新（改 follow-up、补 feedback、PUT 编辑）
-- 都会刷新，导致面试停留时长被静默重置。新增独立的状态进入时刻列，仅在状态迁移时写入。
-- 兼容 MySQL 5.7 / H2 MySQL mode（单条 ALTER 只改一列，参照 V20 教训：H2 不支持单语句多列 ADD COLUMN）

-- 1) 新增可空列（历史数据由下方回填）
ALTER TABLE application_record ADD COLUMN stage_entered_at DATETIME(3) NULL;

-- 2) 历史数据回填（无状态迁移时刻的精确记录，取最好近似）：
--    - APPLIED 及之后状态：用 applied_at（进入这些状态前必然经过投递时刻）
--    - DRAFT：applied_at 为 NULL，回退用 created_at
--    COALESCE 为标准 SQL 函数，MySQL 与 H2 MySQL mode 均支持，无方言函数依赖。
UPDATE application_record
SET stage_entered_at = COALESCE(applied_at, created_at);
