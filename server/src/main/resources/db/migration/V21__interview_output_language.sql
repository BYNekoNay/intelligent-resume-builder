-- Persist the user-selected interview language across answers, retries, and refreshes.
ALTER TABLE interview_session
    ADD COLUMN output_language VARCHAR(16) NOT NULL DEFAULT 'ZH_CN'
    COMMENT 'ZH_CN | EN';
