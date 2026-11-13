ALTER TABLE refresh_tokens ADD COLUMN session_id VARCHAR(36);

UPDATE refresh_tokens
SET session_id = 'legacy-' || token_id
WHERE session_id IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN session_id SET NOT NULL;

CREATE INDEX idx_refresh_tokens_active_session
    ON refresh_tokens (session_id, revoked, expires_at);
