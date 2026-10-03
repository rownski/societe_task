package com.societe.task.application.persistence;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.societe.task.application.domain.Application;
import com.societe.task.application.domain.ApplicationState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApplicationRepository {

    private static final RowMapper<Application> ROW_MAPPER = (result, rowNumber) -> new Application(
            result.getObject("id", UUID.class),
            result.getString("name"),
            result.getString("body"),
            ApplicationState.valueOf(result.getString("state")),
            result.getObject("publication_number", Long.class),
            result.getObject("created_at", OffsetDateTime.class),
            result.getObject("updated_at", OffsetDateTime.class),
            result.getString("rejection_reason"),
            result.getObject("rejected_at", OffsetDateTime.class),
            result.getString("deletion_reason"),
            result.getObject("deleted_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public JdbcApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Application create(UUID id, String name, String body) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO applications (id, name, body, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING *
                """, ROW_MAPPER, id, name, body);
    }

    public long count(String name, ApplicationState state) {
        var filter = filter(name, state);
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM applications " + filter.clause(), Long.class, filter.parameters().toArray());
    }

    public List<Application> list(String name, ApplicationState state, int page, int size) {
        var filter = filter(name, state);
        var parameters = new ArrayList<>(filter.parameters());
        parameters.add(size);
        parameters.add((long) page * size);
        return jdbcTemplate.query("SELECT * FROM applications " + filter.clause()
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", ROW_MAPPER, parameters.toArray());
    }

    private Filter filter(String name, ApplicationState state) {
        var parameters = new ArrayList<Object>();
        var clause = state == null ? "WHERE state <> 'DELETED'" : "WHERE state = ?";
        if (state != null) {
            parameters.add(state.name());
        }
        if (name != null && !name.isBlank()) {
            clause += " AND name ILIKE ? ESCAPE '\\'";
            var escapedName = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            parameters.add("%" + escapedName + "%");
        }
        return new Filter(clause, parameters);
    }

    private record Filter(String clause, List<Object> parameters) {
    }

    // Call within a transaction: the lock serializes all edits and state transitions.
    public Optional<Application> findByIdForUpdate(UUID id) {
        return jdbcTemplate.query("SELECT * FROM applications WHERE id = ? FOR UPDATE", ROW_MAPPER, id)
                .stream().findFirst();
    }

    public Application updateBody(UUID id, String body) {
        return jdbcTemplate.queryForObject("""
                UPDATE applications SET body = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state IN ('CREATED', 'VERIFIED')
                RETURNING *
                """, ROW_MAPPER, body, id);
    }

    public Application changeState(UUID id, ApplicationState current, ApplicationState target) {
        return jdbcTemplate.queryForObject("""
                UPDATE applications SET state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = ?
                RETURNING *
                """, ROW_MAPPER, target.name(), id, current.name());
    }

    public Application reject(UUID id, ApplicationState current, String reason) {
        return jdbcTemplate.queryForObject("""
                UPDATE applications
                SET state = 'REJECTED', rejection_reason = ?,
                    rejected_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = ?
                RETURNING *
                """, ROW_MAPPER, reason, id, current.name());
    }

    public Application publish(UUID id) {
        return jdbcTemplate.queryForObject("""
                UPDATE applications
                SET state = 'PUBLISHED', publication_number = nextval('application_publication_number_seq'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'ACCEPTED'
                RETURNING *
                """, ROW_MAPPER, id);
    }

    public void softDelete(UUID id, String reason) {
        jdbcTemplate.update("""
                UPDATE applications
                SET state = 'DELETED', deletion_reason = ?,
                    deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'CREATED'
                """, reason, id);
    }
}
