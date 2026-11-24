ALTER TABLE horse_registration_history ENABLE ROW LEVEL SECURITY;

-- Supabase-only provisioning; plain PostgreSQL installations configure their own bucket.
DO $$
BEGIN
    IF to_regclass('storage.buckets') IS NOT NULL THEN
        INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
        VALUES ('horse-registration-files','horse-registration-files',false,10485760,
                ARRAY['image/jpeg','image/png','image/webp','application/pdf'])
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
