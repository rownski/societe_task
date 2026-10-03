package com.societe.task.application;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ApplicationStateMigrationTests {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private String schema;

    @BeforeEach
    void migrateToV1InIsolatedSchema() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        flyway().migrate();
    }

    @AfterEach
    void removeTestSchema() {
        jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void freshDatabaseIncludesStateWithCreatedDefault() {
        var id = insertApplication();

        assertThat(stateOf(id)).isEqualTo("CREATED");
        assertThat(flyway().info().current().getVersion().getVersion()).isEqualTo("1");
        var application = jdbcTemplate.queryForMap("SELECT * FROM " + table() + " WHERE id = ?", id);
        assertThat(application).containsEntry("name", "Test application").containsEntry("body", "Test body");
        assertThat(application.get("rejection_reason")).isNull();
        assertThat(application.get("rejected_at")).isNull();
        assertThat(application.get("deletion_reason")).isNull();
        assertThat(application.get("deleted_at")).isNull();
    }

    @Test
    void rerunningMigrationsDoesNotChangeExistingData() {
        var id = insertApplication();
        var before = jdbcTemplate.queryForMap("SELECT * FROM " + table() + " WHERE id = ?", id);

        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM " + table() + " WHERE id = ?", id)).isEqualTo(before);
    }

    @Test
    void databaseConstraintsRequireValidStatesAndMatchingMetadata() {
        var id = insertApplication();
        for (var invalidState : new String[]{"OTHER", "REJECTED", "DELETED"}) {
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table() + " SET state = ? WHERE id = ?", invalidState, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table() + " SET state = NULL WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table()
                + " SET deletion_reason = 'DUPLICATE', deleted_at = CURRENT_TIMESTAMP WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table()
                + " SET rejection_reason = 'Missing documents', rejected_at = CURRENT_TIMESTAMP WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table()
                + " SET state = 'REJECTED', rejection_reason = 'Missing documents', rejected_at = CURRENT_TIMESTAMP, "
                + "deletion_reason = 'DUPLICATE', deleted_at = CURRENT_TIMESTAMP WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stateOf(id)).isEqualTo("CREATED");
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load();
    }

    private UUID insertApplication() {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + table() + " (id, name, body, created_at, updated_at) "
                + "VALUES (?, 'Test application', 'Test body', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id);
        return id;
    }

    private String stateOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT state FROM " + table() + " WHERE id = ?", String.class, id);
    }

    private String table() {
        return schema + ".applications";
    }
}
