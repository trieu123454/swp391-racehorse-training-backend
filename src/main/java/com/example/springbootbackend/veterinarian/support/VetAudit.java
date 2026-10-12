package com.example.springbootbackend.veterinarian.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class VetAudit {
    private final JdbcTemplate db;
    private final VetTime time;
    public VetAudit(JdbcTemplate db, VetTime time) { this.db = db; this.time = time; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long actorId, String ip, String action) {
        db.update("INSERT INTO audit_logs(id,user_id,action_performed,ip_address,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), actorId, truncate(action, 255), truncate(ip, 45), time.utcNow());
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        int count = value.codePointCount(0, value.length());
        return count <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
}
