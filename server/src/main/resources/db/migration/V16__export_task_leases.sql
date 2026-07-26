ALTER TABLE export_task ADD COLUMN lease_owner VARCHAR(128) NULL;
ALTER TABLE export_task ADD COLUMN lease_expires_at DATETIME(3) NULL;
CREATE INDEX idx_export_task_claim ON export_task (status, lease_expires_at, id);
