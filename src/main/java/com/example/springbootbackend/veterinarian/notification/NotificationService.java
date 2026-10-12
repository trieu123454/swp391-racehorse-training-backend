package com.example.springbootbackend.veterinarian.notification;

import com.example.springbootbackend.veterinarian.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Shared by all roles; future reminders stay hidden until scheduled_at. */
@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final JdbcTemplate db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    public NotificationService(JdbcTemplate db, VetAccess access, VetAudit audit, VetTime time) {
        this.db = db; this.access = access; this.audit = audit; this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String schedule(long recipient, String relatedTable, String relatedId, String message, Instant scheduledAt) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO notifications(id,user_id,related_table,related_id,message,is_read,scheduled_at,created_at) VALUES (?,?,?,?,?,FALSE,?,?)",
                id, recipient, relatedTable, relatedId, message, VetRows.sqlValue(scheduledAt), time.utcNow());
        return id;
    }

    public Map<String, Object> list(String email, boolean unreadOnly, ApiPage page) {
        long user = access.requireActiveUser(email);
        var now = time.utcNow();
        String where = " WHERE user_id=? AND COALESCE(scheduled_at,created_at)<=?";
        Long unread = db.queryForObject("SELECT count(*) FROM notifications" + where + " AND is_read=FALSE", Long.class, user, now);
        if (unreadOnly) where += " AND is_read=FALSE";
        Long total = db.queryForObject("SELECT count(*) FROM notifications" + where, Long.class, user, now);
        var rows = db.query("SELECT * FROM notifications" + where
                + " ORDER BY COALESCE(scheduled_at,created_at) DESC,created_at DESC,id DESC LIMIT ? OFFSET ?",
                VetRows.MAPPER, user, now, page.limit(), page.offset());
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit(), "unread_count", unread);
    }

    @Transactional
    public Map<String, Object> read(String email, String ip, UUID id) {
        long user = access.requireActiveUser(email);
        var rows = db.queryForList("SELECT id FROM notifications WHERE id=? AND user_id=? "
                + "AND COALESCE(scheduled_at,created_at)<=? FOR UPDATE", id.toString(), user, time.utcNow());
        if (rows.isEmpty()) throw new VetException(404, "NOT_FOUND", "Không tìm thấy thông báo");
        db.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?", id.toString(), user);
        audit.record(user, ip, "READ_NOTIFICATION:" + id);
        return Map.of("id", id, "is_read", true);
    }

    @Transactional
    public Map<String, Object> readAll(String email, String ip) {
        long user = access.requireActiveUser(email);
        int updated = db.update("UPDATE notifications SET is_read=TRUE WHERE user_id=? AND is_read=FALSE "
                + "AND COALESCE(scheduled_at,created_at)<=?", user, time.utcNow());
        audit.record(user, ip, "READ_ALL_NOTIFICATIONS:" + user);
        return Map.of("updated", updated);
    }
}
