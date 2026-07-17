CREATE TABLE interview_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    resume_version_id BIGINT NULL,
    external_resume_text MEDIUMTEXT NULL,
    job_description_id BIGINT NOT NULL,
    interview_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_question TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_interview_session_user_updated (user_id, updated_at),
    CONSTRAINT fk_interview_session_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_interview_session_resume FOREIGN KEY (resume_version_id) REFERENCES resume_version(id),
    CONSTRAINT fk_interview_session_job FOREIGN KEY (job_description_id) REFERENCES job_description(id)
);
CREATE TABLE interview_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    question_text TEXT NOT NULL,
    answer_text MEDIUMTEXT NOT NULL,
    round_score INT NOT NULL,
    feedback_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_interview_record_session_created (session_id, created_at),
    CONSTRAINT fk_interview_record_session FOREIGN KEY (session_id) REFERENCES interview_session(id)
);
