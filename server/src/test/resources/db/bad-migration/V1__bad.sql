-- 故意引用不存在的表,用于验证迁移失败时应用无法启动
ALTER TABLE non_existent_table_for_test ADD COLUMN bad_col VARCHAR(1);
