-- V40: 投递记录与面试回答资产纳入留存清扫（决策 D2 延伸批，B7）。
--
-- 两张表此前没有 deleted_at：用户删除是**硬删**（ApplicationService#delete /
-- InterviewAssetService#delete 直接 repository.delete），与 docs/04 §7.1
-- 「软删 30 天恢复期 + 7 天宽限期后清扫」的承诺不一致 —— 用户误删即不可逆。
-- 本迁移只加列与索引，不做数据回填（历史硬删行已不存在，无回填对象）。
--
-- ⚠ 编号说明：V40 原在阶段六计划中预留给 reminder 迁移，现由本批占用；
-- reminder 迁移顺延为 V41（见 docs/plans 与仓库长期记忆）。
--
-- 索引：清扫候选查询按 deleted_at 过滤、账户级视角按 user 归属，
-- 组合索引 (user_id, deleted_at) 同时服务两方。

ALTER TABLE application_record
    ADD COLUMN deleted_at DATETIME(3) NULL;

CREATE INDEX idx_application_user_deleted ON application_record(user_id, deleted_at);

ALTER TABLE interview_answer_asset
    ADD COLUMN deleted_at DATETIME(3) NULL;

CREATE INDEX idx_interview_asset_user_deleted ON interview_answer_asset(user_id, deleted_at);
