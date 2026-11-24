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
