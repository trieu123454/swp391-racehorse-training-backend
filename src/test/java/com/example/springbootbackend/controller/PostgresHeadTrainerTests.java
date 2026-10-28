package com.example.springbootbackend.controller;

import com.example.springbootbackend.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run the workout lifecycle and concurrency cases against disposable PostgreSQL tables. */
@EnabledIfSystemProperty(named = "training.postgres", matches = "true")
class PostgresHeadTrainerTests extends HeadTrainerControllerTests {
    private static PostgresTestDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        database = PostgresTestDatabase.create("training_test_");
        database.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (database != null) database.close();
    }
}
