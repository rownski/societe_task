package com.societe.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SocieteTaskApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReportsUpWithPostgres() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @Transactional
    void canWriteAndReadPostgres() {
        jdbcTemplate.execute("CREATE TEMPORARY TABLE connection_check (id INTEGER PRIMARY KEY, value TEXT) ON COMMIT DROP");
        jdbcTemplate.update("INSERT INTO connection_check (id, value) VALUES (?, ?)", 1, "connected");

        var value = jdbcTemplate.queryForObject(
                "SELECT value FROM connection_check WHERE id = ?", String.class, 1);

        assertThat(value).isEqualTo("connected");
        assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL 17");
    }
}
