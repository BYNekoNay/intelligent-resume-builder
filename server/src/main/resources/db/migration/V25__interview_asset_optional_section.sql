-- V25: 允许答案资产只关联职业素材而不伪造简历章节。
-- 先放开非空约束，再把历史空字符串 sentinel 转成真正的 NULL。
ALTER TABLE interview_asset_section MODIFY COLUMN section_key VARCHAR(32) NULL;
UPDATE interview_asset_section SET section_key = NULL WHERE section_key = '';
