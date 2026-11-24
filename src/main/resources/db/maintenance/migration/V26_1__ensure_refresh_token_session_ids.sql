ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS session_id VARCHAR(36);

UPDATE refresh_tokens
SET session_id = CAST(token_id AS VARCHAR(36))
WHERE session_id IS NULL;

ALTER TABLE refresh_tokens
    ALTER COLUMN session_id SET NOT NULL;
