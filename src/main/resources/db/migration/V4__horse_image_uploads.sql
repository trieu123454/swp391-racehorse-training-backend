CREATE TABLE horse_image_uploads (
    object_path VARCHAR(512) PRIMARY KEY,
    uploaded_by BIGINT NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
