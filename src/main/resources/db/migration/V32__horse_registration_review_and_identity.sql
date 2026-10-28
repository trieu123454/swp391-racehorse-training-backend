ALTER TABLE horse_registration_requests
    ADD COLUMN revision_no INTEGER NOT NULL DEFAULT 1;
ALTER TABLE horse_registration_requests
    ADD COLUMN transfer_consent BOOLEAN;
ALTER TABLE horse_registration_requests
    ADD COLUMN transfer_consent_by BIGINT REFERENCES users (user_id) ON DELETE SET NULL;
ALTER TABLE horse_registration_requests
    ADD COLUMN transfer_consent_at TIMESTAMP;
ALTER TABLE horse_registration_requests
    ADD COLUMN transfer_consent_note TEXT;

ALTER TABLE horse_registration_requests
    ALTER COLUMN status TYPE VARCHAR(32);

ALTER TABLE horse_registration_requests
    DROP CONSTRAINT ck_horse_registration_request_status;
ALTER TABLE horse_registration_requests
    ADD CONSTRAINT ck_horse_registration_request_status
    CHECK (status IN ('PENDING', 'NEEDS_INFORMATION', 'AWAITING_OWNER_CONSENT', 'APPROVED', 'REJECTED'));

ALTER TABLE horse_registration_documents
    ADD COLUMN document_type VARCHAR(30) NOT NULL DEFAULT 'OTHER';
ALTER TABLE horse_registration_documents
    ADD COLUMN revision_no INTEGER NOT NULL DEFAULT 1;
ALTER TABLE horse_registration_documents
    ADD CONSTRAINT ck_horse_registration_document_type
    CHECK (document_type IN ('OWNERSHIP_PROOF', 'HORSE_CERTIFICATE', 'HEALTH_DOCUMENT', 'OTHER'));

ALTER TABLE horses ADD COLUMN microchip_number VARCHAR(100);
ALTER TABLE horses ADD COLUMN passport_number VARCHAR(100);

UPDATE horses h
SET microchip_number = (
    SELECT r.microchip_number
    FROM horse_registration_requests r
    WHERE r.approved_horse_id = h.id
      AND r.status = 'APPROVED'
      AND r.microchip_number IS NOT NULL
    ORDER BY r.reviewed_at DESC, r.created_at DESC, r.id DESC
    LIMIT 1
)
WHERE EXISTS (
    SELECT 1 FROM horse_registration_requests r
    WHERE r.approved_horse_id = h.id AND r.status = 'APPROVED'
      AND r.microchip_number IS NOT NULL
);

UPDATE horses h
SET passport_number = (
    SELECT r.passport_number
    FROM horse_registration_requests r
    WHERE r.approved_horse_id = h.id
      AND r.status = 'APPROVED'
      AND r.passport_number IS NOT NULL
    ORDER BY r.reviewed_at DESC, r.created_at DESC, r.id DESC
    LIMIT 1
)
WHERE EXISTS (
    SELECT 1 FROM horse_registration_requests r
    WHERE r.approved_horse_id = h.id AND r.status = 'APPROVED'
      AND r.passport_number IS NOT NULL
);

CREATE INDEX idx_horses_microchip_number ON horses (microchip_number);
CREATE INDEX idx_horses_passport_number ON horses (passport_number);
CREATE INDEX idx_horse_registration_request_review_queue
    ON horse_registration_requests (status, approved_horse_id, created_at);

CREATE TABLE horse_registration_request_history (
    id CHAR(36) PRIMARY KEY,
    request_id CHAR(36) NOT NULL REFERENCES horse_registration_requests (id) ON DELETE CASCADE,
    revision_no INTEGER NOT NULL,
    action VARCHAR(40) NOT NULL,
    actor_id BIGINT REFERENCES users (user_id) ON DELETE SET NULL,
    note TEXT,
    snapshot_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_horse_registration_history_action CHECK (
        action IN ('SUBMITTED', 'NEEDS_INFORMATION', 'RESUBMITTED', 'REVIEW_APPROVED',
                   'REJECTED', 'OWNER_CONSENT', 'OWNER_DECLINED', 'MIGRATED_STATE')
    )
);

CREATE INDEX idx_horse_registration_history_request
    ON horse_registration_request_history (request_id, created_at, id);

CREATE TABLE horse_identity_lock (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);
INSERT INTO horse_identity_lock(id) VALUES (1);
