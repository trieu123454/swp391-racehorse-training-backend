package com.example.springbootbackend.veterinarian;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in: run the same integration cases in an isolated, disposable PostgreSQL schema. */
@EnabledIfSystemProperty(named="vet.postgres", matches="true")
class PostgresVeterinarianTests extends VeterinarianTests {
    private static String schema;
    private static Properties config;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        config=new Properties();
        try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { config.load(reader); }
        schema="vet_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=connect(); var statement=connection.createStatement()) { statement.execute("CREATE SCHEMA "+schema); }
        String url=config.getProperty("spring.datasource.url");
        String scopedUrl=url+(url.contains("?")?"&":"?")+"currentSchema="+schema+"&prepareThreshold=0";
        registry.add("spring.datasource.url",()->scopedUrl);
        registry.add("spring.datasource.username",()->config.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password",()->config.getProperty("spring.datasource.password"));
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.default-schema",()->schema);
        registry.add("spring.flyway.schemas",()->schema);
        registry.add("spring.jpa.properties.hibernate.default_schema",()->schema);
        registry.add("logging.level.org.springframework.jdbc",()->"WARN");
        registry.add("logging.level.org.springframework",()->"WARN");
    }
    @AfterAll
    static void cleanupSchema() throws Exception {
        if(schema==null || !schema.matches("vet_test_[a-f0-9]{32}")) return;
        try(var connection=connect(); var statement=connection.createStatement()) { statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(config.getProperty("spring.datasource.url"),config.getProperty("spring.datasource.username"),config.getProperty("spring.datasource.password"));
    }
}
