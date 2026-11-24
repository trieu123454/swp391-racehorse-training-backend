-- V42 deployments removed these tables after their original migrations were recorded.
-- Restore the relational API schema without touching requests, documents or inline history.
CREATE TABLE IF NOT EXISTS horse_registration_history (
    id CHAR(36) PRIMARY KEY,
    request_id CHAR(36) NOT NULL REFERENCES horse_registration_requests(id) ON DELETE CASCADE,
    actor_id BIGINT NOT NULL REFERENCES users(user_id),
    action VARCHAR(30) NOT NULL,
    review_note TEXT,
    from_status VARCHAR(30),
    to_status VARCHAR(30),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_registration_history_request ON horse_registration_history(request_id,created_at);

CREATE TABLE IF NOT EXISTS owner_ownership_transfer_requests (
    id CHAR(36) PRIMARY KEY,
    requester_id BIGINT NOT NULL REFERENCES users(user_id),
    horse_id CHAR(36) NOT NULL REFERENCES horses(id),
    new_owner_id BIGINT NOT NULL REFERENCES users(user_id),
    reason VARCHAR(2000),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (requester_id <> new_owner_id)
);
CREATE INDEX IF NOT EXISTS idx_owner_transfer_requester ON owner_ownership_transfer_requests(requester_id,created_at);
CREATE INDEX IF NOT EXISTS idx_owner_transfer_horse ON owner_ownership_transfer_requests(horse_id,status);
