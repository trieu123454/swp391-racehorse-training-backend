CREATE TABLE horse_registration_requests (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    request_type VARCHAR(20) NOT NULL,
    horse_name VARCHAR(100) NOT NULL,
    breed VARCHAR(50),
    birth_year INTEGER,
    height_cm DECIMAL(5,1),
    current_weight_kg DECIMAL(6,2),
    pedigree_father VARCHAR(100),
    pedigree_mother VARCHAR(100),
    microchip_number VARCHAR(100),
    passport_number VARCHAR(100),
    owner_note TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_horse_id CHAR(36) REFERENCES horses (id) ON DELETE SET NULL,
    reviewed_by BIGINT REFERENCES users (user_id) ON DELETE SET NULL,
    review_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP,
    CONSTRAINT ck_horse_registration_request_type CHECK (request_type IN ('REGISTER', 'LINK')),
    CONSTRAINT ck_horse_registration_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE TABLE horse_registration_documents (
    id CHAR(36) PRIMARY KEY,
    request_id CHAR(36) NOT NULL REFERENCES horse_registration_requests (id) ON DELETE CASCADE,
    object_path VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_horse_registration_requests_owner ON horse_registration_requests (owner_id, created_at);
CREATE INDEX idx_horse_registration_requests_status ON horse_registration_requests (status, created_at);
CREATE INDEX idx_horse_registration_documents_request ON horse_registration_documents (request_id);

CREATE TABLE horse_ownership_history (
    id CHAR(36) PRIMARY KEY,
    horse_id CHAR(36) NOT NULL REFERENCES horses (id) ON DELETE CASCADE,
    previous_owner_id BIGINT REFERENCES users (user_id) ON DELETE SET NULL,
    new_owner_id BIGINT REFERENCES users (user_id) ON DELETE SET NULL,
    changed_by BIGINT REFERENCES users (user_id) ON DELETE SET NULL,
    change_reason VARCHAR(40) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_horse_ownership_history_horse ON horse_ownership_history (horse_id, changed_at);

INSERT INTO horse_ownership_history (id, horse_id, previous_owner_id, new_owner_id, changed_by, change_reason, changed_at)
SELECT id, id, NULL, owner_id, NULL, 'BASELINE', COALESCE(created_at, CURRENT_TIMESTAMP)
FROM horses
WHERE owner_id IS NOT NULL;
