-- V34: 全站配额观测计数索引（ideation #524 取证后续）
-- gauge resume_ai_quota_daily_attempts 的查询形状为 task_type = ? AND created_at > ?：
-- 在 MySQL 5.7.24 + 20 万行 ai_task 上实测为全表扫描（type=ALL rows≈199191，62~66ms/次；
-- Prometheus 每次抓取都会对每个任务类型各执行一次）；
-- 补 (task_type, created_at) 后同一探针为 range + Using index condition（0.22~0.29ms），
-- 且 worker 领取查询仍走 PRIMARY（ORDER BY id LIMIT 早停），无计划回退。
-- 兼容 MySQL 5.7 / H2 MySQL mode：单条语句只建一个索引（参照 V20 教训）。
CREATE INDEX idx_ai_task_type_created ON ai_task(task_type, created_at);