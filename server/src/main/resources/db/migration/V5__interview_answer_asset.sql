CREATE TABLE interview_answer_asset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    interview_record_id BIGINT NULL,
    question_text TEXT NOT NULL,
    original_answer_text TEXT NOT NULL,
    suggested_answer_text TEXT NULL,
    feedback_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_interview_asset_user_created (user_id, created_at),
    CONSTRAINT fk_interview_asset_user FOREIGN KEY (user_id) REFERENCES user(id)
);
