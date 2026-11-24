ALTER TABLE horse_registration_documents ADD COLUMN IF NOT EXISTS document_type VARCHAR(30) NOT NULL DEFAULT 'OTHER';
ALTER TABLE horse_registration_documents DROP CONSTRAINT IF EXISTS ck_horse_registration_document_type;
ALTER TABLE horse_registration_documents ADD CONSTRAINT ck_horse_registration_document_type
    CHECK (document_type IN ('OWNERSHIP_PROOF','HORSE_CERTIFICATE','HEALTH_DOCUMENT','OTHER'));
CREATE TABLE horse_registration_history (
    id CHAR(36) PRIMARY KEY,
    request_id CHAR(36) NOT NULL REFERENCES horse_registration_requests(id) ON DELETE CASCADE,
    actor_id BIGINT NOT NULL REFERENCES users(user_id),
    action VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_registration_history_request ON horse_registration_history(request_id,created_at);

ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS revision_no INTEGER NOT NULL DEFAULT 1;
ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS history_json TEXT;
ALTER TABLE horse_registration_documents ADD COLUMN IF NOT EXISTS revision_no INTEGER NOT NULL DEFAULT 1;
ALTER TABLE horse_registration_requests DROP CONSTRAINT IF EXISTS ck_horse_registration_request_status;
ALTER TABLE horse_registration_requests ADD CONSTRAINT ck_horse_registration_request_status
    CHECK (status IN ('PENDING','NEEDS_INFORMATION','AWAITING_OWNER_CONSENT','APPROVED','REJECTED'));

ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS horse_image_filename VARCHAR(255);
ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS horse_image_content_type VARCHAR(100);
ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS horse_image_file_size BIGINT;
