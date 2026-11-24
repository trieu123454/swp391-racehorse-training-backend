-- Keep historical message text for compatibility. New writers store template facts instead.
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS type VARCHAR(50);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS title VARCHAR(150);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS params TEXT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS link VARCHAR(2048);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread_due ON notifications(user_id,is_read,scheduled_at);
