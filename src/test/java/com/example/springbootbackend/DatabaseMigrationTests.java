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
    @Test
    void restoresOwnerRequestTablesAfterRecordedMigrationsWithoutLosingInlineHistory() throws Exception {
        String url=databaseUrl();
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration")
                .target("28.15").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO roles(role_name) VALUES ('HORSE_OWNER')");
            s.executeUpdate("INSERT INTO users(user_id,full_name,email,password_hash,role_id,status) SELECT 501,'Owner','retained@example.com','hash',role_id,'APPROVED' FROM roles WHERE role_name='HORSE_OWNER'");
            s.executeUpdate("INSERT INTO horse_registration_requests(id,owner_id,request_type,horse_name,history_json) VALUES ('retained-request',501,'REGISTER','Retained horse','[{\"action\":\"SUBMITTED\"}]')");
            s.executeUpdate("DROP TABLE horse_registration_history");
            s.executeUpdate("DROP TABLE owner_ownership_transfer_requests");
            s.executeUpdate("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) SELECT max(installed_rank)+1,'42','Existing schema','SQL','V42__existing.sql',0,'sa',0,TRUE FROM flyway_schema_history");
        }
        var flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").outOfOrder(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO horse_registration_history(id,request_id,actor_id,action,review_note,from_status,to_status) VALUES ('retained-history','retained-request',501,'SUBMITTED','note',NULL,'PENDING')");
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V28_16__restore_owner_request_tables.sql"));
            try(var r=s.executeQuery("SELECT history_json,(SELECT count(*) FROM horse_registration_history) FROM horse_registration_requests WHERE id='retained-request'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("[{\"action\":\"SUBMITTED\"}]");
                assertThat(r.getInt(2)).isEqualTo(1);
            }
            try(var r=s.executeQuery("SELECT count(*) FROM owner_ownership_transfer_requests")) {
                assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isZero();
            }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test
    void restoresHistoryWhenEarlierRestoreIsRecordedButTableWasRemovedByNewerDeployment() throws Exception {
        String url=databaseUrl();
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration")
                .target("28.11").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO horses(id,horse_name) VALUES ('existing-horse','Existing horse')");
            s.executeUpdate("ALTER TABLE health_exams ADD COLUMN edit_history_json TEXT");
            s.executeUpdate("INSERT INTO health_exams(id,horse_id,temperature_c,edit_history_json) VALUES ('existing-exam','existing-horse',38.5,'[{\"legacy\":true}]')");
            s.executeUpdate("DROP TABLE health_exam_logs");
            s.executeUpdate("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
                    +"SELECT max(installed_rank)+1,'42','Existing newer schema','SQL','V42__existing.sql',0,'sa',0,TRUE FROM flyway_schema_history");
        }
        var flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").outOfOrder(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT temperature_c,edit_history_json,EXISTS(SELECT 1 FROM health_exam_logs l WHERE l.health_exam_id=e.id) AS has_edits FROM health_exams e WHERE id='existing-exam'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBigDecimal(1)).isEqualByComparingTo("38.5");
                assertThat(r.getString(2)).isEqualTo("[{\"legacy\":true}]");
                assertThat(r.getBoolean(3)).isFalse();
            }
            s.executeUpdate("INSERT INTO health_exam_logs(id,health_exam_id,old_data_json,new_data_json) VALUES ('retained-log','existing-exam','{}','{}')");
            // Reapplying the idempotent DDL must preserve existing relational history too.
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V28_13__restore_relational_health_exam_history.sql"));
            try(var r=s.executeQuery("SELECT count(*) FROM health_exam_logs WHERE id='retained-log'")) {
                assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private String databaseUrl() {
        return "jdbc:h2:mem:schema_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private Flyway migrations(String url) {
        return Flyway.configure().dataSource(url, "sa", "").baselineOnMigrate(true)
                .baselineVersion("1").locations("classpath:db/migration").load();
    }

    @Test
    void compatibilityMigrationPreservesRequestsOnAnExistingNewerSchema() throws Exception {
        String url=databaseUrl();
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("28").load().migrate();
        try(Connection c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO roles(role_name) VALUES ('HORSE_OWNER')");
            s.executeUpdate("INSERT INTO users(user_id,full_name,email,password_hash,role_id,status) SELECT 500,'Owner','legacy@example.com','hash',role_id,'APPROVED' FROM roles WHERE role_name='HORSE_OWNER'");
            s.executeUpdate("INSERT INTO horse_registration_requests(id,owner_id,request_type,horse_name) VALUES ('00000000-0000-0000-0000-000000000001',500,'REGISTER','Existing request')");
            s.executeUpdate("DROP TABLE horse_ownership_history");
            s.executeUpdate("DROP TABLE health_exam_logs");
            s.executeUpdate("DROP TABLE horse_image_uploads");
            // V32 on the deployed V40 database already supplies these columns.
            s.executeUpdate("ALTER TABLE horse_registration_documents ADD COLUMN document_type VARCHAR(30) NOT NULL DEFAULT 'OTHER'");
            s.executeUpdate("ALTER TABLE horse_registration_requests ADD COLUMN history_json TEXT");
            s.executeUpdate("ALTER TABLE horse_registration_requests ADD COLUMN revision_no INTEGER NOT NULL DEFAULT 1");
            s.executeUpdate("ALTER TABLE horse_registration_documents ADD COLUMN revision_no INTEGER NOT NULL DEFAULT 1");
            // Model this maintenance branch being run against an already migrated newer database.
            s.executeUpdate("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
                    +"SELECT max(installed_rank)+1,'40','Existing newer schema','SQL','V40__existing.sql',0,'sa',0,TRUE FROM flyway_schema_history");
        }
        assertThat(Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration")
                .outOfOrder(true).load().migrate().migrationsExecuted).isEqualTo(10);
        try(Connection c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            try(var rows=s.executeQuery("SELECT count(*) FROM health_exam_logs")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            s.executeUpdate("INSERT INTO horse_image_uploads(object_path,uploaded_by) VALUES ('horses/test.png',500)");
            try(var rows=s.executeQuery("SELECT uploaded_by FROM horse_image_uploads WHERE object_path='horses/test.png'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(500);
            }
            try(var rows=s.executeQuery("SELECT horse_name,status FROM horse_registration_requests WHERE owner_id=500")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Existing request");
                assertThat(rows.getString(2)).isEqualTo("PENDING");
            }
            try(var rows=s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version='40' AND success=TRUE")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void freshDatabaseHasCompleteSchemaAndHorseDefaults() throws Exception {
        String url = databaseUrl();
        Flyway flyway = migrations(url);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(33);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var rs = s.executeQuery("select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE'")) {
                var tables = new java.util.ArrayList<String>();
                while (rs.next()) tables.add(rs.getString(1));
                assertThat(tables).containsExactlyInAnyOrder(
                        "roles","users","refresh_tokens","role_change_requests","stable_boxes","horses",
                        "training_plans","training_plan_horses","training_plan_logs","training_schedules","training_metrics_logs",
                        "races","horse_race_entries","training_videos","health_exams","health_exam_logs",
                        "medical_records","prescriptions","diet_records","injury_markers","periodic_care_schedules",
                        "notifications","daily_task_logs","stable_incidents","inventory_items","inventory_lots","inventory_transactions","supply_requests",
                        "financial_reports","audit_logs","horse_image_uploads","calendar_events",
                        "race_simulations","race_simulation_horses","race_simulation_metrics","training_simulation_readings",
                        "horse_registration_requests","horse_registration_documents","horse_registration_history","horse_ownership_history","owner_ownership_transfer_requests","flyway_schema_history");
            }
            try (var rs = s.executeQuery("select numeric_scale from information_schema.columns where table_schema='public' and table_name='race_simulation_horses' and column_name='base_max_speed_kmh'")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isEqualTo(4);
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
        assertThat(migrations(url).migrate().migrationsExecuted).isEqualTo(32);
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
    void calendarMigrationPreservesDatesAndEnforcesLinks() throws Exception {
        String url = databaseUrl();
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("7").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("insert into horses(id,horse_name) values ('horse','Thunder')");
            s.executeUpdate("insert into training_schedules(id,horse_id,training_date,start_time) values ('training','horse','2026-10-01','08:30:00')");
            s.executeUpdate("insert into races(id,race_name,race_date) values ('race','Cup','2026-10-02')");
            s.executeUpdate("insert into horse_race_entries(id,horse_id,race_id) values ('entry','horse','race')");
            s.executeUpdate("insert into periodic_care_schedules(id,horse_id,care_type,next_due_date) values ('care','horse','MedicalCheckup','2026-10-03')");
            s.executeUpdate("insert into daily_task_logs(id,horse_id,task_type,task_date,status) values ('task','horse','Feeding','2026-10-04','Completed')");
        }
        migrations(url).migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var rs = s.executeQuery("select event_date,start_time from calendar_events where source_table='training_schedules'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDate(1).toString()).isEqualTo("2026-10-01");
                assertThat(rs.getTime(2).toString()).isEqualTo("08:30:00");
            }
            try (var rs = s.executeQuery("select event_type,event_date,status from calendar_events order by event_date")) {
                rs.next();
                rs.next(); assertThat(rs.getString(1)).isEqualTo("Race");
                assertThat(rs.getDate(2).toString()).isEqualTo("2026-10-02");
                rs.next(); assertThat(rs.getString(1)).isEqualTo("MedicalCheckup");
                assertThat(rs.getDate(2).toString()).isEqualTo("2026-10-03");
                rs.next(); assertThat(rs.getString(3)).isEqualTo("Completed");
                assertThat(rs.getDate(2).toString()).isEqualTo("2026-10-04");
            }
            assertThatThrownBy(() -> s.executeUpdate("insert into training_schedules(id,horse_id,calendar_event_id) values ('duplicate','horse','training')"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("insert into training_schedules(id,horse_id,calendar_event_id) values ('missing','horse','missing')"))
                    .isInstanceOf(java.sql.SQLException.class);
            s.executeUpdate("delete from calendar_events where id='care'");
            try (var rs = s.executeQuery("select calendar_event_id from periodic_care_schedules where id='care'")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isNull();
            }
            s.executeUpdate("delete from calendar_events where id='training'");
            try (var rs = s.executeQuery("select count(*) from training_schedules")) {
                rs.next(); assertThat(rs.getInt(1)).isZero();
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
