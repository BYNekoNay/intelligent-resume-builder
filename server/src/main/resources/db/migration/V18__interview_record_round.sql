ALTER TABLE interview_record ADD COLUMN round_no INT NULL;

UPDATE interview_record
SET round_no = (
    SELECT ranked.round_no
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) AS round_no
        FROM interview_record
    ) ranked
    WHERE ranked.id = interview_record.id
)
WHERE round_no IS NULL;

ALTER TABLE interview_record MODIFY COLUMN round_no INT NOT NULL;

CREATE UNIQUE INDEX uq_interview_record_session_round
    ON interview_record(session_id, round_no);
