CREATE TABLE IF NOT EXISTS horse_supplement_documents (
    id CHAR(36) PRIMARY KEY,
    horse_id CHAR(36) NOT NULL REFERENCES horses(id) ON DELETE CASCADE,
    object_path VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    document_type VARCHAR(30) NOT NULL DEFAULT 'OTHER',
    note VARCHAR(500),
    uploaded_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    revoked_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    revoke_reason VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_horse_supplement_documents_horse
    ON horse_supplement_documents(horse_id, uploaded_at DESC);
