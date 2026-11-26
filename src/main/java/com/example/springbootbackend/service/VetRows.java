package com.example.springbootbackend.service;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class VetRows {
    private VetRows() {
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T mapRow(ResultSet rs, int rowNum) throws SQLException;
    }

    @FunctionalInterface
    public interface ResultSetExtractor<T> {
        T extractData(ResultSet rs) throws SQLException;
    }

    public static final RowMapper<Map<String, Object>> MAPPER = (rs, index) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData metadata = rs.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            Object value = rs.getObject(column);
            if (value != null && metadata.getColumnType(column) == Types.TIMESTAMP) {
                LocalDateTime local = rs.getObject(column, LocalDateTime.class);
                // shortcut: injury_markers.marked_at stores Asia/Ho_Chi_Minh wall time while other TIMESTAMP columns store UTC; upgrade if schema migrates to TIMESTAMPTZ
                value = "marked_at".equalsIgnoreCase(metadata.getColumnLabel(column))
                    ? local.atZone(VetTime.BUSINESS_ZONE).toInstant()
                    : local.toInstant(ZoneOffset.UTC);
            } else if (value != null && metadata.getColumnType(column) == Types.CHAR)
                value = value.toString().stripTrailing();
            else if (value != null && metadata.getColumnType(column) == Types.DATE)
                value = rs.getObject(column, LocalDate.class);
            else if (value != null && metadata.getColumnType(column) == Types.TIME)
                value = rs.getObject(column, LocalTime.class);
            row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), value);
        }
        return row;
    };

    public static Object sqlValue(Object value) {
        return value instanceof Instant instant ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : value;
    }

    public static Object person(Object id, Object name) {
        if (id == null) return null;
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("id", id);
        person.put("name", name);
        return person;
    }
}
