CREATE TABLE password_reset_codes (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    email VARCHAR(100) NOT NULL,
    code VARCHAR(10) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_password_reset_codes_email ON password_reset_codes (email);

