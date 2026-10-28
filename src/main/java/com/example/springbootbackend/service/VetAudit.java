package com.example.springbootbackend.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.springbootbackend.entity.AuditLog;
import com.example.springbootbackend.repository.AuditLogRepository;

@Component
public class VetAudit {
    private final AuditLogRepository auditLogRepository;
    private final VetTime time;

    public VetAudit(AuditLogRepository auditLogRepository, VetTime time) {
        this.auditLogRepository = auditLogRepository;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long actorId, String ip, String action) {
        AuditLog log = new AuditLog(
                UUID.randomUUID().toString(),
                actorId,
                truncate(action, 255),
                truncate(ip, 45)
        );
        log.setCreatedAt(time.utcNow());
        auditLogRepository.saveAndFlush(log);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        int count = value.codePointCount(0, value.length());
        return count <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
}
