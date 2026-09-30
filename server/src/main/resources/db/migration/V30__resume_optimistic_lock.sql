-- V30: resume 新增 version（乐观锁，ideation finding #38）
-- 背景：resume.current_version_id 的指针写入虽已在服务层用行锁串行化（#100），
-- 但标题/软删等路径会保存陈旧实体，把并发推进的 current_version_id 一起写回（静默回退）。
-- 加 @Version 后陈旧副本保存抛乐观锁冲突 → 全局处理器映射 40901。
-- 兼容 MySQL 5.7 / H2 MySQL mode：单条 ALTER 只加一列（参照 V20 教训）。
ALTER TABLE resume ADD COLUMN version BIGINT NOT NULL DEFAULT 0;