-- Additive compatibility schema for the local workflows after the consolidated V42 schema.
-- Compatibility with the inventory catalog introduced on the deployed V29 schema.
ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS item_code VARCHAR(30);
ALTER TABLE supply_requests ADD COLUMN IF NOT EXISTS quantity_approved DECIMAL(10,2);
ALTER TABLE supply_requests ADD COLUMN IF NOT EXISTS review_note TEXT;
CREATE TABLE IF NOT EXISTS inventory_lots (
    id CHAR(36) PRIMARY KEY,
    item_id CHAR(36) NOT NULL REFERENCES inventory_items(id),
    lot_number VARCHAR(80),
    expiry_date DATE,
    quantity_received DECIMAL(10,2) NOT NULL,
    quantity_remaining DECIMAL(10,2) NOT NULL,
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    received_by BIGINT REFERENCES users(user_id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS inventory_transactions (
    id CHAR(36) PRIMARY KEY,
    item_id CHAR(36) NOT NULL REFERENCES inventory_items(id),
    lot_id CHAR(36) REFERENCES inventory_lots(id),
    actor_id BIGINT REFERENCES users(user_id),
    transaction_type VARCHAR(20) NOT NULL,
    quantity_change DECIMAL(10,2) NOT NULL,
    quantity_before DECIMAL(10,2) NOT NULL,
    quantity_after DECIMAL(10,2) NOT NULL,
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- V40 removed the upload provenance table still required by this backend's
-- upload and horse-profile write authorization checks.
CREATE TABLE IF NOT EXISTS horse_image_uploads (
    object_path VARCHAR(512) PRIMARY KEY,
    uploaded_by BIGINT NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Newer deployments consolidated history after V28.8 had already been recorded.
-- Use the maintenance branch version range with out-of-order migration enabled.
-- A new version is necessary: Flyway must not rerun or alter applied migrations.
-- Preserve health_exams, including inline edit_history_json and last-edit metadata.
CREATE TABLE IF NOT EXISTS health_exam_logs (
    id CHAR(36) PRIMARY KEY,
    health_exam_id CHAR(36) NOT NULL REFERENCES health_exams(id) ON DELETE CASCADE,
    edited_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    old_data_json TEXT,
    new_data_json TEXT,
    edited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_health_exam_logs_health_exam_id ON health_exam_logs(health_exam_id);
CREATE INDEX IF NOT EXISTS idx_health_exam_logs_edited_by ON health_exam_logs(edited_by);

-- Keep historical message text for compatibility. New writers store template facts instead.
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS type VARCHAR(50);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS title VARCHAR(150);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS params TEXT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS link VARCHAR(2048);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread_due ON notifications(user_id,is_read,scheduled_at);

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

-- The deployed V40 schema removed this V27 table. Registration approval needs a
-- durable ownership event alongside the request review event on both schemas.
CREATE TABLE IF NOT EXISTS horse_ownership_history (
    id CHAR(36) PRIMARY KEY,
    horse_id CHAR(36) NOT NULL REFERENCES horses(id) ON DELETE CASCADE,
    previous_owner_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    new_owner_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    changed_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    change_reason VARCHAR(40) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_horse_ownership_history_horse ON horse_ownership_history(horse_id,changed_at);

ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS horse_image_filename VARCHAR(255);
ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS horse_image_content_type VARCHAR(100);
ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS horse_image_file_size BIGINT;
