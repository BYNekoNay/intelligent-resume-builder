-- 沟通模板乐观锁（ideation #73）。
--
-- 自定义模板的并发内容更新此前是「最后写入覆盖」；加版本列后由 JPA @Version
-- 检出并发写，冲突经全局处理器映射 40901。使用计数（usage_count）改走原子
-- 自增 UPDATE，不参与版本推进，避免并发计数产生伪冲突。
ALTER TABLE communication_template ADD COLUMN version BIGINT NOT NULL DEFAULT 0;