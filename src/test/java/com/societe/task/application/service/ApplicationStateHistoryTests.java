package com.societe.task.application.service;

import com.societe.task.application.domain.ApplicationState;
import com.societe.task.application.support.ApplicationApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationStateHistoryTests extends ApplicationApiTestSupport {

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void rollingBackCreationRemovesApplicationAndHistory() {
        var application = new TransactionTemplate(transactionManager).execute(transaction -> {
            var created = applicationService.create("Rolled back", "Test body");
            assertThat(stateHistory(created.id())).hasSize(1);
            transaction.setRollbackOnly();
            return created;
        });

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM applications WHERE id = ?",
                Long.class, application.id())).isZero();
        assertThat(stateHistory(application.id())).isEmpty();
    }

    @ParameterizedTest(name = "audit failure during creation: {0}")
    @ValueSource(booleans = {true, false})
    void auditInsertFailureRollsBackApplicationMutation(boolean creation) throws Exception {
        var id = applicationInState(ApplicationState.CREATED);
        var before = storedApplication(id);
        var historyBefore = stateHistory(id);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            // PostgreSQL rolls back this temporary constraint too, restoring the shared test schema.
            jdbcTemplate.execute("ALTER TABLE application_state_history ADD CONSTRAINT fail_audit_insert "
                    + "CHECK (new_state NOT IN ('CREATED', 'VERIFIED')) NOT VALID");
            if (creation) {
                applicationService.create("Must not persist", "Test body");
            } else {
                applicationService.verify(id);
            }
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM applications", Long.class)).isEqualTo(1);
        assertThat(storedApplication(id)).isEqualTo(before);
        assertThat(stateHistory(id)).isEqualTo(historyBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM application_state_history", Long.class)).isEqualTo(1);

        // Confirm the failure-injection constraint was rolled back and normal auditing still works.
        applicationService.verify(id);
        assertThat(stateHistory(id)).hasSize(2);
    }
}
