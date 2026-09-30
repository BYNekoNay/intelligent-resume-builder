-- ideation #26：AI 任务留存清理的幂等标记。
--
-- 终态任务超过保留期后，内联快照（input_snapshot_json，含内联 JD/资料/简历）与结果
-- （result_json，单任务可达 64KB）会被压缩为元数据；snapshot_purged 记录该行已完成压缩，
-- 避免清理作业每轮重复命中同一批行（快照被替换为占位 JSON 后无法再用 SQL 判断是否压缩过）。
ALTER TABLE ai_task ADD COLUMN snapshot_purged BOOLEAN NOT NULL DEFAULT FALSE;