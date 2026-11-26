package com.example.springbootbackend.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

@Component
public class VetTime {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public LocalDate today() {
        return now().atZone(BUSINESS_ZONE).toLocalDate();
    }

    public LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(now(), ZoneOffset.UTC);
    }

    public LocalDateTime utcStartOf(LocalDate businessDate) {
        return LocalDateTime.ofInstant(businessDate.atStartOfDay(BUSINESS_ZONE).toInstant(), ZoneOffset.UTC);
    }

    public static LocalDateTime toBusinessLocal(LocalDateTime utcDateTime) {
        return utcDateTime == null
            ? null
            : utcDateTime.atOffset(ZoneOffset.UTC).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
    }
}
