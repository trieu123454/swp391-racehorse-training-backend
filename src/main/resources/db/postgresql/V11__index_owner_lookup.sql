CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_users_owner_lookup_role_status
    ON users (role_id,user_id)
    WHERE status='APPROVED' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_owner_lookup_name_trgm
    ON users USING GIN (lower(full_name) gin_trgm_ops)
    WHERE status='APPROVED' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_owner_lookup_email_trgm
    ON users USING GIN (lower(email) gin_trgm_ops)
    WHERE status='APPROVED' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_owner_lookup_phone_trgm
    ON users USING GIN (lower(COALESCE(phone,'')) gin_trgm_ops)
    WHERE status='APPROVED' AND deleted_at IS NULL;
