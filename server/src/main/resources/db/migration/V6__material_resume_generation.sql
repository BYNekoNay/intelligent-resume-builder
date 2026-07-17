CREATE TABLE material_resume_generation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    raw_material_text MEDIUMTEXT NOT NULL,
    job_description_id BIGINT NULL,
    generated_resume_json JSON NOT NULL,
    suggestions_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_material_generation_user_created (user_id, created_at),
    CONSTRAINT fk_material_generation_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_material_generation_job FOREIGN KEY (job_description_id) REFERENCES job_description(id)
);
