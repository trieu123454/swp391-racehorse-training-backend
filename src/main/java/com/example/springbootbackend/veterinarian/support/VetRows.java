package com.example.springbootbackend.veterinarian.support;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;

public final class VetRows {
    private VetRows() {}
    public static final RowMapper<Map<String, Object>> MAPPER = (rs, index) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData metadata = rs.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            Object value = rs.getObject(column);
            if (value != null && metadata.getColumnType(column) == Types.TIMESTAMP)
                value = rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
            else if (value != null && metadata.getColumnType(column) == Types.CHAR)
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
        person.put("id", id); person.put("name", name);
        return person;
    }
}
