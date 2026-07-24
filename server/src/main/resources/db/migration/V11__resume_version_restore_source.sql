ALTER TABLE resume_version
    ADD COLUMN restored_from_version_id BIGINT NULL;

CREATE INDEX idx_resume_version_restored_from
    ON resume_version(restored_from_version_id);

ALTER TABLE resume_version
    ADD CONSTRAINT fk_resume_version_restored_from
        FOREIGN KEY (restored_from_version_id) REFERENCES resume_version(id);
