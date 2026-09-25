import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/** Run from the backend directory. Credentials are read locally and never printed. */
class MigrateDatabase {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("--check") || args[0].equals("--apply"))) {
            throw new IllegalArgumentException("Usage: MigrateDatabase.java --check|--apply");
        }
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("application-local.properties"))) {
            config.load(reader);
        }
        String url = config.getProperty("spring.datasource.url");
        url += (url.contains("?") ? "&" : "?") + "prepareThreshold=0";
        String username = config.getProperty("spring.datasource.username");
        String password = config.getProperty("spring.datasource.password");
        if (args[0].equals("--check")) {
            // Transactional DDL verifies PostgreSQL syntax without keeping any changes.
            String schema = "migration_check_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection c = DriverManager.getConnection(url, username, password)) {
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                    s.execute("CREATE SCHEMA " + schema);
                    s.execute("SET LOCAL search_path TO " + schema);
                    for (String script : new String[]{
                            "db/migration/V1__create_authentication_schema.sql",
                            "db/migration/V2__create_business_schema.sql",
                            "db/postgresql/V3__secure_business_tables.sql",
                            "db/migration/V4__horse_image_uploads.sql",
                            "db/postgresql/V5__secure_horse_image_uploads.sql"}) {
                        s.execute(Files.readString(Path.of("src/main/resources", script)));
                    }
                    try (var rs = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='" + schema + "' AND table_type='BASE TABLE'")) {
                        rs.next();
                        if (rs.getInt(1) != 28) throw new IllegalStateException("Unexpected table count");
                    }
                    System.out.println("PASS: PostgreSQL DDL, 28 application tables, compatibility view and RLS.");
                } finally {
                    c.rollback();
                }
                System.out.println("Temporary schema rolled back; public data unchanged.");
            }
            return;
        }

        String before;
        long usersBefore;
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            before = fingerprint(c);
            usersBefore = countUsers(c);
        }
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
                .schemas("public").defaultSchema("public")
                .baselineOnMigrate(true).baselineVersion("1")
                .locations("filesystem:src/main/resources/db/migration", "filesystem:src/main/resources/db/postgresql")
                .load();
        var result = flyway.migrate();
        flyway.validate();
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            if (!java.util.Objects.equals(before, fingerprint(c)) || usersBefore != countUsers(c)) {
                throw new IllegalStateException("Authentication data changed unexpectedly; inspect database before proceeding");
            }
            try (var s = c.createStatement(); var rs = s.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")) {
                rs.next(); System.out.println("Public tables including Flyway history: " + rs.getInt(1));
            }
            System.out.println("PASS: migrations=" + result.migrationsExecuted + "; existing users, hashes and statuses preserved.");
        }
    }

    private static String fingerprint(Connection c) throws Exception {
        try (var s = c.createStatement(); var rs = s.executeQuery(
                "SELECT md5(string_agg(user_id::text || ':' || email || ':' || password_hash || ':' || status || ':' || role_id::text, ',' ORDER BY user_id)) FROM public.users")) {
            rs.next(); return rs.getString(1);
        }
    }

    private static long countUsers(Connection c) throws Exception {
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM public.users")) {
            rs.next(); return rs.getLong(1);
        }
    }
}
