ALTER TABLE ats_check_result ADD COLUMN idempotency_key VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE ats_check_result ADD COLUMN request_fingerprint VARCHAR(128) NOT NULL DEFAULT '';
UPDATE ats_check_result SET idempotency_key = CONCAT('legacy-', id) WHERE idempotency_key = '';
CREATE UNIQUE INDEX uq_ats_check_result_user_idempotency ON ats_check_result(user_id, idempotency_key);
