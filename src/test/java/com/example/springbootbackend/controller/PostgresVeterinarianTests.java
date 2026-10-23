package com.example.springbootbackend.controller;

import com.example.springbootbackend.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in: run the same integration cases in an isolated, disposable PostgreSQL schema. */
@EnabledIfSystemProperty(named = "vet.postgres", matches = "true")
class PostgresVeterinarianTests extends VeterinarianTests {
    private static PostgresTestDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        database = PostgresTestDatabase.create("vet_test_");
        database.configure(registry);
    }

    @AfterAll
    static void cleanupSchema() throws Exception {
        if (database != null) database.close();
    }
}
