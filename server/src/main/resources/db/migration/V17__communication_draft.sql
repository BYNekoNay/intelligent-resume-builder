CREATE TABLE communication_draft (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_version_id BIGINT NOT NULL,
    job_description_id BIGINT NOT NULL,
    draft_type VARCHAR(32) NOT NULL,
    draft_text TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_communication_user_created (user_id, created_at),
    CONSTRAINT fk_communication_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_communication_resume_version FOREIGN KEY (resume_version_id) REFERENCES resume_version(id),
    CONSTRAINT fk_communication_job FOREIGN KEY (job_description_id) REFERENCES job_description(id)
);
