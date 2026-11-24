package com.example.springbootbackend;

import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DatabaseMigrationTests {
  private String databaseUrl() {
    return "jdbc:h2:mem:schema_"
        + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  }

  private Flyway migrations(String url) {
    return Flyway.configure()
        .dataSource(url, "sa", "")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .locations("classpath:db/migration")
        .load();
  }

  @Test
  void compatibilityMigrationPreservesExistingV42RequestsAndCanBeReapplied() throws Exception {
    String url = databaseUrl();
    Flyway.configure()
        .dataSource(url, "sa", "")
        .locations("classpath:db/migration")
        .target("42")
        .load()
        .migrate();
    try (var c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      s.executeUpdate("INSERT INTO roles(role_name) VALUES ('HORSE_OWNER')");
      s.executeUpdate(
          "INSERT INTO users(user_id,full_name,email,password_hash,role_id,status) SELECT"
              + " 501,'Owner','retained@example.com','hash',role_id,'APPROVED' FROM roles WHERE"
              + " role_name='HORSE_OWNER'");
      s.executeUpdate(
          "INSERT INTO"
              + " horse_registration_requests(id,owner_id,request_type,horse_name,history_json)"
              + " VALUES ('retained-request',501,'REGISTER','Retained"
              + " horse','[{\"action\":\"SUBMITTED\"}]')");
    }
    var flyway =
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    try (var c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      s.executeUpdate(
          "INSERT INTO horse_registration_history(id,request_id,actor_id,action,review_note) VALUES"
              + " ('retained-history','retained-request',501,'SUBMITTED','note')");
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("db/migration/V43__integrate_local_workflow_compatibility.sql"));
      try (var r =
          s.executeQuery(
              "SELECT history_json,(SELECT count(*) FROM horse_registration_history) FROM"
                  + " horse_registration_requests WHERE id='retained-request'")) {
        assertThat(r.next()).isTrue();
        assertThat(r.getString(1)).isEqualTo("[{\"action\":\"SUBMITTED\"}]");
        assertThat(r.getInt(2)).isEqualTo(1);
      }
      try (var r = s.executeQuery("SELECT count(*) FROM owner_ownership_transfer_requests")) {
        assertThat(r.next()).isTrue();
        assertThat(r.getInt(1)).isZero();
      }
    }
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void freshDatabaseHasCompleteSchemaAndHorseDefaults() throws Exception {
    String url = databaseUrl();
    Flyway flyway = migrations(url);
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(37);
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      try (var rs =
          s.executeQuery(
              "select table_name from information_schema.tables where table_schema='public' and"
                  + " table_type='BASE TABLE'")) {
        var tables = new java.util.ArrayList<String>();
        while (rs.next()) tables.add(rs.getString(1));
        assertThat(tables)
            .containsExactlyInAnyOrder(
                "roles",
                "users",
                "refresh_tokens",
                "stable_boxes",
                "horses",
                "training_plans",
                "training_plan_horses",
                "training_schedules",
                "training_metrics_logs",
                "races",
                "horse_race_entries",
                "health_exams",
                "medical_records",
                "prescriptions",
                "diet_records",
                "injury_markers",
                "periodic_care_schedules",
                "notifications",
                "daily_task_logs",
                "stable_incidents",
                "inventory_items",
                "inventory_lots",
                "inventory_transactions",
                "supply_requests",
                "financial_reports",
                "audit_logs",
                "calendar_events",
                "race_simulations",
                "race_simulation_horses",
                "horse_registration_requests",
                "horse_registration_documents",
                "flyway_schema_history",
                "horse_image_uploads",
                "health_exam_logs",
                "horse_registration_history",
                "owner_ownership_transfer_requests",
                "horse_ownership_history");
      }
      try (var rs =
          s.executeQuery(
              "select numeric_scale from information_schema.columns where table_schema='public' and"
                  + " table_name='race_simulation_horses' and column_name='base_max_speed_kmh'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(4);
      }
      try (var rs =
          s.executeQuery(
              "select character_maximum_length from information_schema.columns where"
                  + " table_schema='public' and table_name='horse_registration_requests' and"
                  + " column_name='status'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(32);
      }
      for (String identityColumn : new String[] {"microchip_number", "passport_number"}) {
        try (var rs =
            s.executeQuery(
                "select count(*) from information_schema.columns where table_schema='public' and"
                    + " table_name='horses' and column_name='"
                    + identityColumn
                    + "'")) {
          rs.next();
          assertThat(rs.getInt(1)).isEqualTo(1);
        }
      }
      s.executeUpdate(
          "insert into horses(id, horse_name) values ('00000000-0000-0000-0000-000000000001',"
              + " 'Thunder')");
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
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("db/migration/V1__create_authentication_schema.sql"));
      s.executeUpdate("insert into roles(role_name) values ('HORSE_OWNER'),('GROOM')");
      s.executeUpdate(
          "insert into users(user_id,full_name,email,password_hash,role_id,status) values"
              + " (17,'Owner','owner@example.com','unchanged-hash',1,'APPROVED')");
    }
    assertThat(migrations(url).migrate().migrationsExecuted).isEqualTo(36);
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      try (var rs = s.executeQuery("select * from users where user_id=17")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("password_hash")).isEqualTo("unchanged-hash");
        assertThat(rs.getString("status")).isEqualTo("APPROVED");
      }
      s.executeUpdate("update users set role_id=2 where user_id=17");
      try (var rs = s.executeQuery("select role_id from user_roles where user_id=17")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(2);
      }
    }
  }

  @Test
  void softDeletePreservesTrainingAndMedicalHistory() throws Exception {
    String url = databaseUrl();
    migrations(url).migrate();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      s.executeUpdate(
          "insert into horses(id,horse_name,image_url) values"
              + " ('horse-1','Thunder','https://example.com/horse.png')");
      s.executeUpdate(
          "insert into training_plans(id,horse_id,stage_name) values ('plan-1','horse-1','Stage"
              + " 1')");
      s.executeUpdate(
          "insert into medical_records(id,horse_id,diagnosis) values"
              + " ('medical-1','horse-1','Healthy')");
      s.executeUpdate("update horses set deleted_at=CURRENT_TIMESTAMP where id='horse-1'");
      for (String table : new String[] {"training_plans", "medical_records"}) {
        try (var rs = s.executeQuery("select count(*) from " + table)) {
          rs.next();
          assertThat(rs.getInt(1)).isEqualTo(1);
        }
      }
      try (var rs = s.executeQuery("select image_url from horses where id='horse-1'")) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("https://example.com/horse.png");
      }
    }
  }

  @Test
  void calendarMigrationPreservesDatesAndEnforcesLinks() throws Exception {
    String url = databaseUrl();
    Flyway.configure()
        .dataSource(url, "sa", "")
        .locations("classpath:db/migration")
        .target("7")
        .load()
        .migrate();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      s.executeUpdate("insert into horses(id,horse_name) values ('horse','Thunder')");
      s.executeUpdate(
          "insert into training_schedules(id,horse_id,training_date,start_time) values"
              + " ('training','horse','2026-10-01','08:30:00')");
      s.executeUpdate(
          "insert into races(id,race_name,race_date) values ('race','Cup','2026-10-02')");
      s.executeUpdate(
          "insert into horse_race_entries(id,horse_id,race_id) values ('entry','horse','race')");
      s.executeUpdate(
          "insert into periodic_care_schedules(id,horse_id,care_type,next_due_date) values"
              + " ('care','horse','MedicalCheckup','2026-10-03')");
      s.executeUpdate(
          "insert into daily_task_logs(id,horse_id,task_type,task_date,status) values"
              + " ('task','horse','Feeding','2026-10-04','Completed')");
    }
    migrations(url).migrate();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      try (var rs =
          s.executeQuery(
              "select event_date,start_time from calendar_events where"
                  + " source_table='training_schedules'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getDate(1).toString()).isEqualTo("2026-10-01");
        assertThat(rs.getTime(2).toString()).isEqualTo("08:30:00");
      }
      try (var rs =
          s.executeQuery(
              "select event_type,event_date,status from calendar_events order by event_date")) {
        rs.next();
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("Race");
        assertThat(rs.getDate(2).toString()).isEqualTo("2026-10-02");
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("MedicalCheckup");
        assertThat(rs.getDate(2).toString()).isEqualTo("2026-10-03");
        rs.next();
        assertThat(rs.getString(3)).isEqualTo("Completed");
        assertThat(rs.getDate(2).toString()).isEqualTo("2026-10-04");
      }
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into training_schedules(id,horse_id,calendar_event_id) values"
                          + " ('duplicate','horse','training')"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into training_schedules(id,horse_id,calendar_event_id) values"
                          + " ('missing','horse','missing')"))
          .isInstanceOf(java.sql.SQLException.class);
      s.executeUpdate("delete from calendar_events where id='care'");
      try (var rs =
          s.executeQuery("select calendar_event_id from periodic_care_schedules where id='care'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isNull();
      }
      s.executeUpdate("delete from calendar_events where id='training'");
      try (var rs = s.executeQuery("select count(*) from training_schedules")) {
        rs.next();
        assertThat(rs.getInt(1)).isZero();
      }
    }
  }

  @Test
  void rejectsInvalidReferencesAndValues() throws Exception {
    String url = databaseUrl();
    migrations(url).migrate();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into horses(id,horse_name,owner_id) values"
                          + " ('horse-1','Thunder',999)"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () -> s.executeUpdate("insert into horses(id,horse_name) values ('horse-2','   ')"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into stable_boxes(id,box_code,capacity) values ('box-1','A1',0)"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into races(id,race_name,race_date,status) values ('race-bad','Bad"
                          + " Cup','2026-10-10','Invalid')"))
          .isInstanceOf(java.sql.SQLException.class);
    }
  }
}
