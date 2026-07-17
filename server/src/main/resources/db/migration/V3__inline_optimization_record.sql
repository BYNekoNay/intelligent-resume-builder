CREATE TABLE inline_optimization_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_version_id BIGINT NOT NULL,
    job_description_id BIGINT NULL,
    section_code VARCHAR(64) NOT NULL,
    original_content TEXT NOT NULL,
    result_json JSON NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_inline_optimization_user_created (user_id, created_at),
    INDEX idx_inline_optimization_resume_version (resume_version_id),
    CONSTRAINT fk_inline_optimization_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_inline_optimization_resume_version FOREIGN KEY (resume_version_id) REFERENCES resume_version(id),
    CONSTRAINT fk_inline_optimization_job FOREIGN KEY (job_description_id) REFERENCES job_description(id)
);
