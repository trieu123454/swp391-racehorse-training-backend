package com.example.springbootbackend;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

class DatabaseMigrationTests {
    private String databaseUrl() {
        return "jdbc:h2:mem:schema_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private Flyway migrations(String url) {
        return Flyway.configure().dataSource(url, "sa", "").baselineOnMigrate(true)
                .baselineVersion("1").locations("classpath:db/migration").load();
    }

    @Test
    void freshDatabaseHasCompleteSchemaAndHorseDefaults() throws Exception {
        String url = databaseUrl();
        Flyway flyway = migrations(url);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var rs = s.executeQuery("select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'")) {
                rs.next(); assertThat(rs.getInt(1)).isEqualTo(29); // 28 application tables + Flyway
            }
            s.executeUpdate("insert into horses(id, horse_name) values ('00000000-0000-0000-0000-000000000001', 'Thunder')");
            try (var rs = s.executeQuery("select * from horses")) {
                rs.next();
                assertThat(rs.getString("current_status")).isEqualTo("Healthy");
                assertThat(rs.getString("readiness_status")).isEqualTo("Unknown");
                assertThat(rs.getBoolean("is_training_locked")).isFalse();
                assertThat(rs.getString("image_url")).isNull();
                assertThat(rs.getTimestamp("deleted_at")).isNull();
            }
        }
    }

    @Test
    void migrationPreservesExistingAuthenticationDataAndRoleViewTracksChanges() throws Exception {
        String url = databaseUrl();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V1__create_authentication_schema.sql"));
            s.executeUpdate("insert into roles(role_id,role_name) values (1,'HORSE_OWNER'),(2,'GROOM')");
            s.executeUpdate("insert into users(user_id,full_name,email,password_hash,role_id,status) values (17,'Owner','owner@example.com','unchanged-hash',1,'APPROVED')");
        }
        assertThat(migrations(url).migrate().migrationsExecuted).isEqualTo(3);
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var rs = s.executeQuery("select * from users where user_id=17")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("password_hash")).isEqualTo("unchanged-hash");
                assertThat(rs.getString("status")).isEqualTo("APPROVED");
            }
            s.executeUpdate("update users set role_id=2 where user_id=17");
            try (var rs = s.executeQuery("select role_id from user_roles where user_id=17")) {
                rs.next(); assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void softDeletePreservesTrainingAndMedicalHistory() throws Exception {
        String url = databaseUrl(); migrations(url).migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("insert into horses(id,horse_name,image_url) values ('horse-1','Thunder','https://example.com/horse.png')");
            s.executeUpdate("insert into training_plans(id,horse_id,stage_name) values ('plan-1','horse-1','Stage 1')");
            s.executeUpdate("insert into medical_records(id,horse_id,diagnosis) values ('medical-1','horse-1','Healthy')");
            s.executeUpdate("update horses set deleted_at=CURRENT_TIMESTAMP where id='horse-1'");
            for (String table : new String[]{"training_plans", "medical_records"}) {
                try (var rs = s.executeQuery("select count(*) from " + table)) {
                    rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
            try (var rs = s.executeQuery("select image_url from horses where id='horse-1'")) {
                rs.next(); assertThat(rs.getString(1)).isEqualTo("https://example.com/horse.png");
            }
        }
    }

    @Test
    void rejectsInvalidReferencesAndValues() throws Exception {
        String url = databaseUrl(); migrations(url).migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("insert into horses(id,horse_name,owner_id) values ('horse-1','Thunder',999)"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("insert into horses(id,horse_name) values ('horse-2','   ')"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("insert into stable_boxes(id,box_code,capacity) values ('box-1','A1',0)"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
