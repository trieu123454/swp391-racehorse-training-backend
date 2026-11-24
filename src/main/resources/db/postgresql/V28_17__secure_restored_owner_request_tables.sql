-- Match the original API-only access policy on restored tables.
ALTER TABLE horse_registration_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner_ownership_transfer_requests ENABLE ROW LEVEL SECURITY;
