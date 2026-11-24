-- Independent pending transfer submissions do not modify the horse's current owner.
CREATE TABLE owner_ownership_transfer_requests (
    id CHAR(36) PRIMARY KEY,
    requester_id BIGINT NOT NULL REFERENCES users(user_id),
    horse_id CHAR(36) NOT NULL REFERENCES horses(id),
    new_owner_id BIGINT NOT NULL REFERENCES users(user_id),
    reason VARCHAR(2000),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (requester_id <> new_owner_id)
);
CREATE INDEX idx_owner_transfer_requester ON owner_ownership_transfer_requests(requester_id,created_at);
CREATE INDEX idx_owner_transfer_horse ON owner_ownership_transfer_requests(horse_id,status);
