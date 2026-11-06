-- Thêm vai trò Trưởng ban thú y (HEAD_VETERINARIAN)
INSERT INTO roles (role_name, description)
VALUES ('HEAD_VETERINARIAN', 'Trưởng ban thú y - Quản lý và điều phối chuyên môn')
ON CONFLICT (role_name) DO NOTHING;
