-- V36: expand the application workflow and persist status audit history.
-- application_record.status is VARCHAR(32), so existing six-state rows remain readable.
CREATE TABLE application_status_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    application_record_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    note TEXT NULL,
    changed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_application_status_history_application_changed (application_record_id, changed_at),
    CONSTRAINT fk_application_status_history_record
        FOREIGN KEY (application_record_id) REFERENCES application_record(id) ON DELETE CASCADE
);

-- Existing records have no transition log. Preserve their current known stage timestamp as
-- the first observable status event; from_status stays NULL because earlier states are unknown.
INSERT INTO application_status_history (application_record_id, from_status, to_status, changed_at)
SELECT id, NULL, status, COALESCE(stage_entered_at, created_at)
FROM application_record;
