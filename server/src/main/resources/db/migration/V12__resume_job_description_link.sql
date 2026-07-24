-- V12: 简历关联岗位描述，支持"岗位定制简历"自动创建
ALTER TABLE resume ADD COLUMN job_description_id BIGINT NULL AFTER current_version_id;
ALTER TABLE resume ADD CONSTRAINT fk_resume_job_description
    FOREIGN KEY (job_description_id) REFERENCES job_description(id);
CREATE INDEX idx_resume_user_jd ON resume(user_id, job_description_id);
