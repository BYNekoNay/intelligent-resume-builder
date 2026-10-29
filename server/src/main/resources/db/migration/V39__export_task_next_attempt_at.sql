-- V39: 「下次可领取时刻」，让导出任务能按 pdf-service 的 Retry-After 延迟重排。
--
-- 背景：pdf-service 在队列打满 / drain 时返回 503 + `Retry-After: 2`（见
-- pdf-service/src/server.js），语义是「现在没名额，等一会儿同样的请求就能成功」。
-- 但 API 侧此前把它压平成普通失败：任务当场 FAILED，用户必须自己回页面点重试 ——
-- 服务端明确说了等多久，执行侧却当成终态，这是语义断链。
--
-- 有了这一列，任务可以回到 PENDING 并带上「何时才可再领」：
--   · 不忽略服务端给的等待时长（否则等于对下游做重试风暴）；
--   · 也不长期占着 RUNNING 假装在跑（用户会看到永远转圈）。
--
-- NULL = 立即可领取（既有行为，回滚兼容：旧版本忽略该列，仍按 PENDING 领取）。
ALTER TABLE export_task ADD COLUMN next_attempt_at DATETIME(3) NULL;

-- 领取条件会变成 `... AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())`，
-- 给延迟重排的任务加个索引，避免全表按 id 扫描时被大量未到期的行拖慢。
CREATE INDEX idx_export_task_next_attempt ON export_task (status, next_attempt_at);
