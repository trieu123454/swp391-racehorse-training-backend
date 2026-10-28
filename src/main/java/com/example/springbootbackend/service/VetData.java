package com.example.springbootbackend.service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.springbootbackend.dto.request.ApiPage;
import com.example.springbootbackend.exception.VetException;

import jakarta.persistence.EntityManager;

/** Table and column names here come exclusively from service constants, never request values. */
@Component
public class VetData {
    public final VetData db = this;
    private final EntityManager entityManager;
    private final VetAccess access;
    private final TransactionTemplate transactionTemplate;
    private final SQLExceptionTranslator exceptionTranslator = new SQLStateSQLExceptionTranslator();

    public VetData(EntityManager entityManager, VetAccess access, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.access = access;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private <T> T executeSql(String sql, SqlWork<T> work, boolean write) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return doExecuteSql(sql, work, write);
        }
        return transactionTemplate.execute(status -> doExecuteSql(sql, work, write));
    }

    private <T> T doExecuteSql(String sql, SqlWork<T> work, boolean write) {
        boolean inTx = TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (inTx) {
            entityManager.flush();
        }
        Session session = entityManager.unwrap(Session.class);
        T result = session.doReturningWork(connection -> {
            try {
                return work.execute(connection);
            } catch (SQLException ex) {
                DataAccessException translated = new SQLErrorCodeSQLExceptionTranslator(
                        connection.getMetaData().getDatabaseProductName()).translate("VetData", sql, ex);
                if (translated != null) {
                    throw translated;
                }
                DataAccessException fallback = exceptionTranslator.translate("VetData", sql, ex);
                if (fallback != null) {
                    throw fallback;
                }
                throw ex;
            }
        });
        if (write && inTx) {
            entityManager.clear();
        }
        return result;
    }

    public boolean isPostgreSQL() {
        return Boolean.TRUE.equals(executeSql("isPostgreSQL",
                connection -> "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()), false));
    }

    private void bindParameters(PreparedStatement ps, Object... args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, VetRows.sqlValue(args[i]));
        }
    }

    public <T> List<T> query(String sql, VetRows.RowMapper<T> rowMapper) {
        return query(sql, rowMapper, new Object[0]);
    }

    public <T> List<T> query(String sql, VetRows.RowMapper<T> rowMapper, Object... args) {
        return executeSql(sql, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindParameters(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> results = new ArrayList<>();
                    int rowNum = 0;
                    while (rs.next()) {
                        results.add(rowMapper.mapRow(rs, rowNum++));
                    }
                    return results;
                }
            }
        }, false);
    }

    public <T> T query(String sql, VetRows.ResultSetExtractor<T> extractor, Object... args) {
        return executeSql(sql, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindParameters(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return extractor.extractData(rs);
                }
            }
        }, false);
    }

    public List<Map<String, Object>> queryForList(String sql) {
        return queryForList(sql, new Object[0]);
    }

    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            ResultSetMetaData metadata = rs.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                Object value = rs.getObject(column);
                if (value instanceof java.sql.Clob clob) {
                    value = clob.getSubString(1, (int) clob.length());
                } else if (value != null && metadata.getColumnType(column) == Types.CHAR) {
                    value = value.toString().stripTrailing();
                }
                row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), value);
            }
            return row;
        }, args);
    }

    public <T> List<T> queryForList(String sql, Class<T> elementType) {
        return queryForList(sql, elementType, new Object[0]);
    }

    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
        return query(sql, (rs, rowNum) -> extractValue(rs, 1, elementType), args);
    }

    public <T> T queryForObject(String sql, Class<T> requiredType) {
        return queryForObject(sql, requiredType, new Object[0]);
    }

    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        List<T> results = query(sql, (rs, rowNum) -> extractValue(rs, 1, requiredType), args);
        if (results.isEmpty()) {
            throw new EmptyResultDataAccessException(1);
        }
        return results.getFirst();
    }

    private <T> T extractValue(ResultSet rs, int columnIndex, Class<T> requiredType) throws SQLException {
        Object value = rs.getObject(columnIndex);
        if (value == null) {
            return null;
        }
        if (requiredType == String.class) {
            if (value instanceof java.sql.Clob clob) {
                return requiredType.cast(clob.getSubString(1, (int) clob.length()));
            }
            String str = value.toString();
            if (rs.getMetaData().getColumnType(columnIndex) == Types.CHAR) {
                str = str.stripTrailing();
            }
            return requiredType.cast(str);
        }
        if (requiredType == Long.class && value instanceof Number n) {
            return requiredType.cast(n.longValue());
        }
        if (requiredType == Integer.class && value instanceof Number n) {
            return requiredType.cast(n.intValue());
        }
        if (requiredType == Double.class && value instanceof Number n) {
            return requiredType.cast(n.doubleValue());
        }
        if (requiredType == BigDecimal.class) {
            if (value instanceof BigDecimal bd) return requiredType.cast(bd);
            if (value instanceof Number n) return requiredType.cast(new BigDecimal(n.toString()));
        }
        if (requiredType == Boolean.class && value instanceof Boolean b) {
            return requiredType.cast(b);
        }
        if (requiredType == LocalDate.class) {
            if (value instanceof LocalDate ld) return requiredType.cast(ld);
            if (value instanceof java.sql.Date d) return requiredType.cast(d.toLocalDate());
            return requiredType.cast(rs.getObject(columnIndex, LocalDate.class));
        }
        if (requiredType == LocalTime.class) {
            if (value instanceof LocalTime lt) return requiredType.cast(lt);
            if (value instanceof java.sql.Time t) return requiredType.cast(t.toLocalTime());
            return requiredType.cast(rs.getObject(columnIndex, LocalTime.class));
        }
        if (requiredType == LocalDateTime.class) {
            if (value instanceof LocalDateTime ldt) return requiredType.cast(ldt);
            if (value instanceof java.sql.Timestamp ts) return requiredType.cast(ts.toLocalDateTime());
            return requiredType.cast(rs.getObject(columnIndex, LocalDateTime.class));
        }
        if (requiredType.isInstance(value)) {
            return requiredType.cast(value);
        }
        return rs.getObject(columnIndex, requiredType);
    }

    public int update(String sql, Object... args) {
        return executeSql(sql, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindParameters(ps, args);
                return ps.executeUpdate();
            }
        }, true);
    }

    public Map<String, Object> row(String table, String id, boolean lock) {
        var rows = query("SELECT * FROM " + table + " WHERE id=?", VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "NOT_FOUND", "Không tìm thấy bản ghi");
        access.requireHorse(rows.getFirst().get("horse_id").toString(), lock);
        if (lock) rows = query("SELECT * FROM " + table + " WHERE id=? FOR UPDATE", VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "NOT_FOUND", "Không tìm thấy bản ghi");
        return rows.getFirst();
    }

    public void sameHorse(String table, Object id, String horse) {
        if (id == null) return;
        Long count = queryForObject("SELECT count(*) FROM " + table + " WHERE id=? AND horse_id=?", Long.class, id, horse);
        if (count == null || count == 0) {
            throw VetException.invalid(table.equals("health_exams") ? "health_exam_id" : "medical_record_id", "Bản ghi phải thuộc cùng ngựa");
        }
    }

    public String insert(String table, Map<String, Object> data) {
        data.putIfAbsent("id", UUID.randomUUID().toString());
        update("INSERT INTO " + table + " (" + String.join(",", data.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(data.size(), "?")) + ")", data.values().stream().map(VetRows::sqlValue).toArray());
        return data.get("id").toString();
    }

    public void updateRow(String table, String id, Map<String, Object> data) {
        if (data.isEmpty()) return;
        var args = new ArrayList<Object>(data.values().stream().map(VetRows::sqlValue).toList());
        args.add(id);
        update("UPDATE " + table + " SET " + String.join(",", data.keySet().stream().map(k -> k + "=?").toList()) + " WHERE id=?", args.toArray());
    }

    public Map<String, Object> page(String select, String count, String where, String order, List<Object> args, ApiPage page) {
        Long total = queryForObject(count + where, Long.class, args.toArray());
        var parameters = new ArrayList<>(args);
        parameters.add(page.limit());
        parameters.add(page.offset());
        var rows = query(select + where + order + " LIMIT ? OFFSET ?", VetRows.MAPPER, parameters.toArray());
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit());
    }
}
