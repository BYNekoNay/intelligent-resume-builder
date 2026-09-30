-- 面试资产幂等创建的唯一约束（ideation #24）。
--
-- 背景：InterviewAssetService.create 的幂等语义是「同一 (user_id, interview_record_id)
-- 只保留一条资产」，但此前只有应用层「先查后插」，并发双请求可各自插入一条重复资产。
-- 服务层现在先对面试记录行加锁（findOwnedForUpdate）串行化同记录的并发创建，
-- 本唯一索引作为兜底约束。
--
-- interview_record_id 允许为 NULL（不关联记录的资产不做幂等）：MySQL/H2 的唯一索引
-- 允许多个 NULL，不影响此类资产的创建。
CREATE UNIQUE INDEX uq_interview_asset_user_record
    ON interview_answer_asset(user_id, interview_record_id);