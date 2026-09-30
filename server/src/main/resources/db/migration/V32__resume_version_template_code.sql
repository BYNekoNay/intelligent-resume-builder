-- ideation #50：简历版本列表读模型投影的派生列。
--
-- templateCode 原本由 resume_json.template.code 在 Java 侧经 ResumeTemplateCodes.normalize
-- 白名单归一化派生，列表读取因此必须为每一行加载 resume_json（单行上限 256KB）。
-- 持久化为专列后，列表投影只需读取元数据列。
--
-- 历史行不在迁移中回填：归一化白名单属应用层语义，SQL 无法安全复刻
-- （否则大小写/未知取值会与运行期 normalize 结果漂移）。改由服务端读路径惰性回填（幂等）。
ALTER TABLE resume_version ADD COLUMN template_code VARCHAR(32) NULL;