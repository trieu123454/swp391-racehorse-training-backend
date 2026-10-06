package com.example.springbootbackend;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Opt-in integration tests use a new schema and verify its search path before touching any tables. */
public final class PostgresTestDatabase implements AutoCloseable {
    private final Properties config;
    private final String schema;
    private String scopedUrl;

    private PostgresTestDatabase(Properties config,String prefix) {
        if (!prefix.matches("[a-z][a-z0-9_]*_test_")) throw new IllegalArgumentException("Invalid test schema prefix");
        this.config=config;
        this.schema=prefix+UUID.randomUUID().toString().replace("-","");
        String url=config.getProperty("spring.datasource.url","");
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("PostgreSQL configuration is required");
        String[] parts=url.split("\\?",2);
        String parameters=parts.length<2 ? "" : Arrays.stream(parts[1].split("&"))
                .filter(value->!value.matches("(?i)(currentSchema|prepareThreshold|connectTimeout|socketTimeout)=.*"))
                .collect(Collectors.joining("&"));
        scopedUrl=parts[0]+"?"+(parameters.isEmpty()?"":parameters+"&")+"currentSchema="+schema
                +"&prepareThreshold=0&connectTimeout=15&socketTimeout=30";
    }

    public static PostgresTestDatabase create(String prefix) throws Exception {
        var config=new Properties();
        try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { config.load(reader); }
        var database=new PostgresTestDatabase(config,prefix);
        // Creating the empty schema is the only operation performed before verifying the search path.
        try(var connection=database.connect(database.scopedUrl); var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+database.schema);
        }
        try(var connection=database.connect(database.scopedUrl); var statement=connection.createStatement();
                var result=statement.executeQuery("SELECT current_schema()")) {
            if (!result.next() || !database.schema.equals(result.getString(1)))
                throw new IllegalStateException("Test connection did not select its isolated schema");
        } catch (Exception failure) {
            database.close(); throw failure;
        }
        // Supabase installs pg_trgm in a shared extension schema. Keep the test schema first;
        // expose only the extension namespace needed by the existing index migration.
        try(var connection=database.connect(database.scopedUrl); var statement=connection.createStatement();
                var result=statement.executeQuery("SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='pg_trgm'")) {
            if(result.next()) {
                String extensionSchema=result.getString(1);
                if(!extensionSchema.matches("[a-z][a-z0-9_]*"))
                    throw new IllegalStateException("Unsupported extension schema name");
                database.scopedUrl=database.scopedUrl.replace("currentSchema="+database.schema,
                        "currentSchema="+database.schema+","+extensionSchema);
            }
        } catch (Exception failure) {
            database.close(); throw failure;
        }
        return database;
    }

    public void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->scopedUrl);
        registry.add("spring.datasource.username",()->config.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password",()->config.getProperty("spring.datasource.password"));
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.default-schema",()->schema);
        registry.add("spring.flyway.schemas",()->schema);
        registry.add("spring.jpa.properties.hibernate.default_schema",()->schema);
        registry.add("logging.level.org.springframework",()->"WARN");
        registry.add("logging.level.org.hibernate",()->"WARN");
    }

    private Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url,config.getProperty("spring.datasource.username"),config.getProperty("spring.datasource.password"));
    }

    @Override
    public void close() throws SQLException {
        if (!schema.matches("[a-z][a-z0-9_]*_test_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test schema name");
        try(var connection=connect(scopedUrl); var statement=connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
}
