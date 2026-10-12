package com.example.springbootbackend.veterinarian.support;

import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class VetTime {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    public LocalDate today() { return now().atZone(BUSINESS_ZONE).toLocalDate(); }
    public LocalDateTime utcNow() { return LocalDateTime.ofInstant(now(), ZoneOffset.UTC); }
    public LocalDateTime utcStartOf(LocalDate businessDate) {
        return LocalDateTime.ofInstant(businessDate.atStartOfDay(BUSINESS_ZONE).toInstant(), ZoneOffset.UTC);
    }
}
