package com.example.springbootbackend.veterinarian.exam;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExamAlerts {
    private final Map<String, BigDecimal[]> ranges;
    public ExamAlerts(@Value("${app.vet.normal.temperature-min:37.2}") BigDecimal temperatureMin,
                      @Value("${app.vet.normal.temperature-max:38.5}") BigDecimal temperatureMax,
                      @Value("${app.vet.normal.heart-rate-min:28}") BigDecimal heartMin,
                      @Value("${app.vet.normal.heart-rate-max:44}") BigDecimal heartMax,
                      @Value("${app.vet.normal.respiratory-rate-min:8}") BigDecimal respiratoryMin,
                      @Value("${app.vet.normal.respiratory-rate-max:16}") BigDecimal respiratoryMax) {
        ranges = new LinkedHashMap<>();
        ranges.put("temperature_c", new BigDecimal[]{temperatureMin, temperatureMax});
        ranges.put("heart_rate", new BigDecimal[]{heartMin, heartMax});
        ranges.put("respiratory_rate", new BigDecimal[]{respiratoryMin, respiratoryMax});
        ranges.forEach((field, range) -> {
            if (range[0].compareTo(range[1]) > 0) throw new IllegalArgumentException("Invalid normal range: " + field);
        });
    }

    public List<Map<String, Object>> evaluate(Map<String, Object> exam) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        ranges.forEach((field, range) -> {
            Object value = exam.get(field);
            if (value == null) return;
            BigDecimal number = new BigDecimal(value.toString());
            if (number.compareTo(range[0]) < 0 || number.compareTo(range[1]) > 0)
                alerts.add(Map.of("field", field, "value", value,
                        "normal_range", range[0].toPlainString() + " - " + range[1].toPlainString(), "level", "warning"));
        });
        return alerts;
    }
}
