-- V31: 配额/跟进查询组合索引（ideation finding #28）
-- 逐条对应 repository 的真实查询形状（等值列在前、范围列在后，避免全表/仅单列索引扫描）：
-- 1) ai_task(user_id, task_type, created_at)          → AiTaskRepository.countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter（AI 每日配额）
-- 2) interview_ai_attempt(user_id, created_at)        → InterviewAiAttemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter（面试每日配额）
-- 3) application_record(user_id, next_follow_up_at)   → ApplicationRecordRepository 跟进筛选（followUp=TODAY/OVERDUE）
-- resume(user_id, job_description_id) 已由 V12 的 idx_resume_user_jd 覆盖，不重复建。
-- 兼容 MySQL 5.7 / H2 MySQL mode：单条语句只建一个索引（参照 V20 教训）。
CREATE INDEX idx_ai_task_user_type_created ON ai_task(user_id, task_type, created_at);
CREATE INDEX idx_iai_user_created ON interview_ai_attempt(user_id, created_at);
CREATE INDEX idx_application_user_followup ON application_record(user_id, next_follow_up_at);