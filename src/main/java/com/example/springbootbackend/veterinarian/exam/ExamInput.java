package com.example.springbootbackend.veterinarian.exam;

import com.example.springbootbackend.veterinarian.support.VetException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Explicit allowlist; preserves omitted versus null fields for PATCH. */
public final class ExamInput {
    private ExamInput() {}
    public static final List<String> SHORT_TEXT = List.of("mucous_membrane_color", "skin_turgor",
            "jugular_pulse", "digital_pulse", "gut_sounds", "defecation_frequency",
            "urination_frequency", "hoof_temperature");
    public static final List<String> LONG_TEXT = List.of("gait_assessment", "hematology_result",
            "biochemistry_result", "fecal_test_result", "notes");
    public static final List<String> NUMBERS = List.of("temperature_c", "heart_rate", "respiratory_rate",
            "capillary_refill_sec", "body_condition_score");

    public static Map<String, Object> parse(JsonNode body) {
        if (body == null || !body.isObject()) throw VetException.invalid("body", "Phải là JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : SHORT_TEXT) text(body, result, field, 50);
        for (String field : LONG_TEXT) text(body, result, field, null);
        for (String field : NUMBERS) {
            if (!body.has(field)) continue;
            JsonNode value = body.get(field);
            if (value.isNull()) { result.put(field, null); continue; }
            if (!value.isNumber()) throw VetException.invalid(field, "Phải là số");
            BigDecimal number = value.decimalValue();
            if (field.equals("heart_rate") || field.equals("respiratory_rate")) {
                try { result.put(field, number.intValueExact()); }
                catch (ArithmeticException e) { throw VetException.invalid(field, "Phải là số nguyên hợp lệ"); }
            } else {
                if (number.stripTrailingZeros().scale() > 1)
                    throw VetException.invalid(field, "Tối đa 1 chữ số thập phân theo cấu trúc DB");
                result.put(field, number);
            }
        }
        if (body.has("exam_date")) {
            JsonNode value = body.get("exam_date");
            if (!value.isTextual()) throw VetException.invalid("exam_date", "Phải là thời gian ISO-8601 có múi giờ");
            try { result.put("exam_date", OffsetDateTime.parse(value.textValue()).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)); }
            catch (DateTimeParseException e) { throw VetException.invalid("exam_date", "Phải là thời gian ISO-8601 có múi giờ"); }
        }
        return result;
    }

    private static void text(JsonNode body, Map<String, Object> result, String field, Integer max) {
        if (!body.has(field)) return;
        JsonNode value = body.get(field);
        if (value.isNull()) { result.put(field, null); return; }
        if (!value.isTextual()) throw VetException.invalid(field, "Phải là chuỗi");
        String text = value.textValue();
        if (max != null && text.codePointCount(0, text.length()) > max)
            throw VetException.invalid(field, "Tối đa " + max + " ký tự");
        result.put(field, text);
    }

    public static void validate(Map<String, Object> data, Instant now) {
        range(data, "temperature_c", "30", "45", true);
        range(data, "heart_rate", "10", "250", true);
        range(data, "respiratory_rate", "3", "100", true);
        range(data, "capillary_refill_sec", "0", "10", false);
        range(data, "body_condition_score", "1", "9", false);
        Instant date = (Instant) data.get("exam_date");
        if (date == null || date.isAfter(now.plusSeconds(300)))
            throw VetException.invalid("exam_date", "Không được lớn hơn hiện tại quá 5 phút");
    }

    private static void range(Map<String, Object> data, String field, String min, String max, boolean required) {
        Object value = data.get(field);
        if (value == null) {
            if (required) throw VetException.invalid(field, "Bắt buộc");
            return;
        }
        BigDecimal number = new BigDecimal(value.toString());
        if (number.compareTo(new BigDecimal(min)) < 0 || number.compareTo(new BigDecimal(max)) > 0)
            throw VetException.invalid(field, "Phải nằm trong khoảng " + min + " đến " + max);
    }
}
