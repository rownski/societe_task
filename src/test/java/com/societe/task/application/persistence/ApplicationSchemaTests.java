package com.societe.task.application.persistence;

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
class ApplicationSchemaTests {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private String schema;

    @BeforeEach
    void initializeIsolatedSchema() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void removeTestSchema() {
        jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void freshDatabaseIncludesStateWithCreatedDefault() {
        flyway().migrate();
        var id = insertApplication();

        assertThat(stateOf(id)).isEqualTo("CREATED");
        assertThat(flyway().info().current().getVersion().getVersion()).isEqualTo("2");
        var application = jdbcTemplate.queryForMap("SELECT * FROM " + table() + " WHERE id = ?", id);
        assertThat(application).containsEntry("name", "Test application").containsEntry("body", "Test body");
        assertThat(application.get("rejection_reason")).isNull();
        assertThat(application.get("rejected_at")).isNull();
        assertThat(application.get("deletion_reason")).isNull();
        assertThat(application.get("deleted_at")).isNull();
        assertThat(application.get("publication_number")).isNull();
    }

    @Test
    void databaseConstraintsRequireValidStatesAndMatchingMetadata() {
        flyway().migrate();
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

    @Test
    void publicationNumberMustBePositiveUniqueAndPresentOnlyWhenPublished() {
        flyway().migrate();
        var id = insertApplication();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table() + " SET state = 'PUBLISHED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table() + " SET publication_number = 1 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (var invalidNumber : new long[]{0, -1}) {
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table()
                    + " SET state = 'PUBLISHED', publication_number = ? WHERE id = ?", invalidNumber, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        jdbcTemplate.update("UPDATE " + table() + " SET state = 'PUBLISHED', publication_number = 123 WHERE id = ?", id);
        var other = insertApplication();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + table()
                + " SET state = 'PUBLISHED', publication_number = 123 WHERE id = ?", other))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stateOf(id)).isEqualTo("PUBLISHED");
        assertThat(stateOf(other)).isEqualTo("CREATED");
    }

    @Test
    void v2PreservesExistingApplicationsWithoutInventingHistory() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("1").load().migrate();
        var id = insertApplication();
        jdbcTemplate.update("UPDATE " + table() + " SET state = 'VERIFIED' WHERE id = ?", id);
        var before = jdbcTemplate.queryForMap("SELECT * FROM " + table() + " WHERE id = ?", id);

        var result = flyway().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(flyway().info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM " + table() + " WHERE id = ?", id)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + historyTable(), Long.class)).isZero();
        insertHistory(id, "VERIFIED", "ACCEPTED", null);
        assertThat(jdbcTemplate.queryForObject("SELECT previous_state FROM " + historyTable(), String.class))
                .isEqualTo("VERIFIED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = ?", String.class,
                schema, "idx_application_state_history_application")).contains("(application_id, id)");
    }

    @Test
    void historyRequiresAnApplicationValidStatesAndMatchingReasons() {
        flyway().migrate();
        var id = insertApplication();
        insertHistory(id, null, "CREATED", null);
        insertHistory(id, "CREATED", "VERIFIED", null);
        insertHistory(id, "VERIFIED", "REJECTED", "Missing documents");
        insertHistory(id, "CREATED", "DELETED", "DUPLICATE");

        var entries = jdbcTemplate.queryForList("SELECT * FROM " + historyTable() + " ORDER BY id");
        assertThat(entries).hasSize(4).allSatisfy(entry -> {
            assertThat((Long) entry.get("id")).isPositive();
            assertThat(entry.get("changed_at")).isNotNull();
        });
        assertThat(entries).extracting(entry -> entry.get("id")).doesNotHaveDuplicates();
        assertThatThrownBy(() -> insertHistory(UUID.randomUUID(), null, "CREATED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM " + table() + " WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (var invalid : new String[][]{
                {null, null, null}, {null, "OTHER", null}, {"OTHER", "VERIFIED", null},
                {null, "VERIFIED", null}, {"VERIFIED", "CREATED", null}, {"VERIFIED", "VERIFIED", null},
                {"VERIFIED", "REJECTED", null}, {"VERIFIED", "REJECTED", "  "},
                {"CREATED", "DELETED", null}, {"CREATED", "DELETED", "OTHER"},
                {"CREATED", "VERIFIED", "Unexpected reason"}}) {
            assertThatThrownBy(() -> insertHistory(id, invalid[0], invalid[1], invalid[2]))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + historyTable(), Long.class)).isEqualTo(4);
    }

    private void insertHistory(UUID id, String previousState, String newState, String reason) {
        jdbcTemplate.update("INSERT INTO " + historyTable()
                + " (application_id, previous_state, new_state, reason) VALUES (?, ?, ?, ?)",
                id, previousState, newState, reason);
    }

    private String historyTable() {
        return schema + ".application_state_history";
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
