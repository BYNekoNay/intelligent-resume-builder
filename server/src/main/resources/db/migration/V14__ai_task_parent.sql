ALTER TABLE ai_task
    ADD COLUMN parent_task_id BIGINT NULL;

CREATE INDEX idx_ai_task_parent ON ai_task(parent_task_id);

ALTER TABLE ai_task
    ADD CONSTRAINT fk_ai_task_parent FOREIGN KEY (parent_task_id) REFERENCES ai_task(id);
