-- Thêm vai trò Trưởng ban thú y (HEAD_VETERINARIAN)
INSERT INTO roles (role_name, description)
SELECT 'HEAD_VETERINARIAN', 'Trưởng ban thú y - Quản lý và điều phối chuyên môn'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'HEAD_VETERINARIAN');

ALTER TABLE stable_incidents
    DROP CONSTRAINT ck_stable_incidents_assigned_role;

ALTER TABLE stable_incidents
    ADD CONSTRAINT ck_stable_incidents_assigned_role
    CHECK (assigned_role IS NULL OR assigned_role IN ('HEAD_TRAINER', 'HEAD_VETERINARIAN', 'VETERINARIAN', 'GROOM', 'CLUB_MANAGER'));

