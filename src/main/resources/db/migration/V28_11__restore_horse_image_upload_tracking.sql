-- V40 removed the upload provenance table still required by this backend's
-- upload and horse-profile write authorization checks.
CREATE TABLE IF NOT EXISTS horse_image_uploads (
    object_path VARCHAR(512) PRIMARY KEY,
    uploaded_by BIGINT NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
