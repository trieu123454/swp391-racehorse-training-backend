ALTER TABLE inventory_items ADD COLUMN item_code VARCHAR(40);
ALTER TABLE inventory_items ADD COLUMN description TEXT;
ALTER TABLE inventory_items ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE inventory_items ADD COLUMN updated_by BIGINT;
ALTER TABLE inventory_items ADD CONSTRAINT fk_inventory_items_updated_by FOREIGN KEY (updated_by) REFERENCES users (user_id) ON DELETE SET NULL;

UPDATE inventory_items
SET item_code = 'MAT-' || REPLACE(id, '-', ''),
    reorder_threshold = COALESCE(reorder_threshold, 0),
    quantity_in_stock = COALESCE(quantity_in_stock, 0)
WHERE item_code IS NULL OR reorder_threshold IS NULL OR quantity_in_stock IS NULL;

ALTER TABLE inventory_items ALTER COLUMN item_code SET NOT NULL;
ALTER TABLE inventory_items ALTER COLUMN quantity_in_stock SET NOT NULL;
ALTER TABLE inventory_items ALTER COLUMN reorder_threshold SET NOT NULL;
ALTER TABLE inventory_items ADD CONSTRAINT uq_inventory_items_item_code UNIQUE (item_code);
ALTER TABLE inventory_items ADD CONSTRAINT ck_inventory_items_stock_nonnegative CHECK (quantity_in_stock >= 0);
ALTER TABLE inventory_items ADD CONSTRAINT ck_inventory_items_minimum_nonnegative CHECK (reorder_threshold >= 0);

ALTER TABLE supply_requests ADD COLUMN quantity_approved DECIMAL(10,2);
ALTER TABLE supply_requests ADD COLUMN review_note TEXT;

CREATE TABLE inventory_lots (
    id CHAR(36) PRIMARY KEY,
    item_id CHAR(36) NOT NULL,
    lot_number VARCHAR(80) NOT NULL,
    expiry_date DATE,
    quantity_received DECIMAL(10,2) NOT NULL,
    quantity_remaining DECIMAL(10,2) NOT NULL,
    received_at TIMESTAMP NOT NULL,
    received_by BIGINT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_inventory_lots_item FOREIGN KEY (item_id) REFERENCES inventory_items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_lots_received_by FOREIGN KEY (received_by) REFERENCES users (user_id) ON DELETE SET NULL,
    CONSTRAINT ck_inventory_lots_received_positive CHECK (quantity_received > 0),
    CONSTRAINT ck_inventory_lots_remaining_nonnegative CHECK (quantity_remaining >= 0)
);

CREATE INDEX idx_inventory_lots_item_expiry ON inventory_lots (item_id, expiry_date, received_at);

CREATE TABLE inventory_transactions (
    id CHAR(36) PRIMARY KEY,
    item_id CHAR(36) NOT NULL,
    lot_id CHAR(36),
    actor_id BIGINT NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    quantity_change DECIMAL(10,2) NOT NULL,
    quantity_before DECIMAL(10,2) NOT NULL,
    quantity_after DECIMAL(10,2) NOT NULL,
    reason TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_inventory_transactions_item FOREIGN KEY (item_id) REFERENCES inventory_items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_transactions_lot FOREIGN KEY (lot_id) REFERENCES inventory_lots (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_transactions_actor FOREIGN KEY (actor_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_transactions_change_nonzero CHECK (quantity_change <> 0),
    CONSTRAINT ck_inventory_transactions_before_nonnegative CHECK (quantity_before >= 0),
    CONSTRAINT ck_inventory_transactions_after_nonnegative CHECK (quantity_after >= 0)
);

CREATE INDEX idx_inventory_transactions_item_created ON inventory_transactions (item_id, created_at);
CREATE INDEX idx_inventory_transactions_actor_created ON inventory_transactions (actor_id, created_at);

-- Preserve existing on-hand stock as an opening batch so it can be consumed safely.
INSERT INTO inventory_lots (id, item_id, lot_number, expiry_date, quantity_received, quantity_remaining, received_at, received_by, created_at)
SELECT id, id, 'LEGACY-OPENING', NULL, quantity_in_stock, quantity_in_stock, COALESCE(updated_at, CURRENT_TIMESTAMP), updated_by, COALESCE(updated_at, CURRENT_TIMESTAMP)
FROM inventory_items
WHERE quantity_in_stock > 0;
