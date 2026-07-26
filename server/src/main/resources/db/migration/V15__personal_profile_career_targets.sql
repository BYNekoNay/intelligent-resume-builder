ALTER TABLE personal_profile ADD COLUMN target_role_titles JSON NULL;
ALTER TABLE personal_profile ADD COLUMN target_seniority VARCHAR(128) NULL;
ALTER TABLE personal_profile ADD COLUMN target_industries JSON NULL;
ALTER TABLE personal_profile ADD COLUMN target_work_preferences JSON NULL;
ALTER TABLE personal_profile ADD COLUMN career_positioning_summary TEXT NULL;
