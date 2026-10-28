package com.example.springbootbackend.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.springbootbackend.dto.request.ApiPage;
import com.example.springbootbackend.entity.Notification;
import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.NotificationRepository;

/** Shared by all roles; future reminders stay hidden until scheduled_at. */
@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;

    public NotificationService(
            NotificationRepository notificationRepository,
            VetAccess access,
            VetAudit audit,
            VetTime time
    ) {
        this.notificationRepository = notificationRepository;
        this.access = access;
        this.audit = audit;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String schedule(long recipient, String relatedTable, String relatedId, String message, Instant scheduledAt) {
        String id = UUID.randomUUID().toString();
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(recipient);
        notification.setRelatedTable(relatedTable);
        notification.setRelatedId(relatedId);
        notification.setMessage(message);
        notification.setRead(false);
        notification.setScheduledAt(scheduledAt == null ? null : LocalDateTime.ofInstant(scheduledAt, ZoneOffset.UTC));
        notification.setCreatedAt(time.utcNow());
        notificationRepository.saveAndFlush(notification);
        return id;
    }

    public Map<String, Object> list(String email, boolean unreadOnly, ApiPage page) {
        long user = access.requireActiveUser(email);
        LocalDateTime now = time.utcNow();
        long unread = notificationRepository.countVisibleUnread(user, now);
        long total = notificationRepository.countVisible(user, now, unreadOnly);
        List<Map<String, Object>> rows = notificationRepository
                .findVisible(user, now, unreadOnly, PageRequest.of(page.page() - 1, page.limit()))
                .stream()
                .map(this::toRow)
                .toList();
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit(), "unread_count", unread);
    }

    @Transactional
    public Map<String, Object> read(String email, String ip, UUID id) {
        long user = access.requireActiveUser(email);
        Notification notification = notificationRepository
                .findVisibleByIdForUpdate(id.toString(), user, time.utcNow())
                .orElseThrow(() -> new VetException(404, "NOT_FOUND", "Không tìm thấy thông báo"));
        notification.setRead(true);
        notificationRepository.saveAndFlush(notification);
        audit.record(user, ip, "READ_NOTIFICATION:" + id);
        return Map.of("id", id, "is_read", true);
    }

    @Transactional
    public Map<String, Object> readAll(String email, String ip) {
        long user = access.requireActiveUser(email);
        int updated = notificationRepository.markAllVisibleAsRead(user, time.utcNow());
        audit.record(user, ip, "READ_ALL_NOTIFICATIONS:" + user);
        return Map.of("updated", updated);
    }

    private Map<String, Object> toRow(Notification n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", n.getId() == null ? null : n.getId().stripTrailing());
        row.put("user_id", n.getUserId());
        row.put("related_table", n.getRelatedTable());
        row.put("related_id", n.getRelatedId() == null ? null : n.getRelatedId().stripTrailing());
        row.put("message", n.getMessage());
        row.put("is_read", n.getRead());
        row.put("scheduled_at", n.getScheduledAt() == null ? null : n.getScheduledAt().toInstant(ZoneOffset.UTC));
        row.put("created_at", n.getCreatedAt() == null ? null : n.getCreatedAt().toInstant(ZoneOffset.UTC));
        return row;
    }
}
