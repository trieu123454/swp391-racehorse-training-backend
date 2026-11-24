-- The deployed V40 schema removed this V27 table. Registration approval needs a
-- durable ownership event alongside the request review event on both schemas.
CREATE TABLE IF NOT EXISTS horse_ownership_history (
    id CHAR(36) PRIMARY KEY,
    horse_id CHAR(36) NOT NULL REFERENCES horses(id) ON DELETE CASCADE,
    previous_owner_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    new_owner_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    changed_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    change_reason VARCHAR(40) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_horse_ownership_history_horse ON horse_ownership_history(horse_id,changed_at);
