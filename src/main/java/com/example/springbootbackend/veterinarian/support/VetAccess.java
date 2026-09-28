package com.example.springbootbackend.veterinarian.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VetAccess {
    private final JdbcTemplate db;
    public VetAccess(JdbcTemplate db) { this.db = db; }

    public long requireUser(String email, boolean veterinarianOnly) {
        var rows = db.queryForList("SELECT u.user_id,u.status,u.must_change_password,u.deleted_at,r.role_name "
                + "FROM users u JOIN roles r ON r.role_id=u.role_id WHERE lower(u.email)=lower(?)", email);
        if (rows.isEmpty()) throw new VetException(403, "FORBIDDEN", "Tài khoản không được phép truy cập");
        var user = rows.getFirst();
        if (!"APPROVED".equals(user.get("status")) || user.get("deleted_at") != null
                || Boolean.TRUE.equals(user.get("must_change_password"))
                || (veterinarianOnly && !"VETERINARIAN".equals(user.get("role_name"))))
            throw new VetException(403, "FORBIDDEN", "Tài khoản không được phép truy cập");
        return ((Number) user.get("user_id")).longValue();
    }

    public void requireHorse(String id, boolean lock) {
        var rows = db.queryForList("SELECT id FROM horses WHERE id=? AND deleted_at IS NULL"
                + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new VetException(404, "HORSE_NOT_FOUND", "Không tìm thấy ngựa");
    }
}
