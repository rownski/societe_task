package com.societe.task.application.persistence;

import java.util.UUID;

import com.societe.task.application.domain.ApplicationState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApplicationStateHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcApplicationStateHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // The service transaction commits the application change and this entry together.
    public void record(UUID applicationId, ApplicationState previousState, ApplicationState newState, String reason) {
        jdbcTemplate.update("""
                INSERT INTO application_state_history (application_id, previous_state, new_state, reason)
                VALUES (?, ?, ?, ?)
                """, applicationId, previousState == null ? null : previousState.name(), newState.name(), reason);
    }
}
