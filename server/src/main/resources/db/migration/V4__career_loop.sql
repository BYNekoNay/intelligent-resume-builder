CREATE TABLE ats_check_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_version_id BIGINT NOT NULL,
    job_description_id BIGINT NOT NULL,
    total_score DECIMAL(5,2) NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_ats_check_user_created (user_id, created_at),
    CONSTRAINT fk_ats_check_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_ats_check_resume_version FOREIGN KEY (resume_version_id) REFERENCES resume_version(id),
    CONSTRAINT fk_ats_check_job FOREIGN KEY (job_description_id) REFERENCES job_description(id)
);

CREATE TABLE application_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    job_description_id BIGINT NOT NULL,
    resume_version_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    cover_letter_text TEXT NULL,
    opening_message_text TEXT NULL,
    feedback_text TEXT NULL,
    applied_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_application_user_updated (user_id, updated_at),
    CONSTRAINT fk_application_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_application_job FOREIGN KEY (job_description_id) REFERENCES job_description(id),
    CONSTRAINT fk_application_resume_version FOREIGN KEY (resume_version_id) REFERENCES resume_version(id)
);
