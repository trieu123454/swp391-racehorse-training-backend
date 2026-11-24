-- Requests/documents are exposed through the authenticated Spring API only.
ALTER TABLE horse_registration_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE horse_registration_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner_ownership_transfer_requests ENABLE ROW LEVEL SECURITY;
