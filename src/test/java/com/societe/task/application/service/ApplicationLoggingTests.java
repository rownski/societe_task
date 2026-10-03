package com.societe.task.application.service;

import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.societe.task.application.domain.ApplicationState;
import com.societe.task.application.domain.exception.ApplicationConflictException;
import com.societe.task.application.support.ApplicationApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationLoggingTests extends ApplicationApiTestSupport {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApplicationService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void removeAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void creationIsLoggedOnlyAfterCommitWithoutNameOrBody() {
        var application = new TransactionTemplate(transactionManager).execute(transaction -> {
            var created = applicationService.create("private-name", "private-body");
            assertThat(appender.list).isEmpty();
            return created;
        });

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).isEqualTo("Application created: id=" + application.id() + " state=CREATED");
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationState.class, names = {"VERIFIED", "ACCEPTED", "PUBLISHED", "REJECTED", "DELETED"})
    void successfulTransitionsAreLoggedOnlyAfterCommit(ApplicationState target) throws Exception {
        var source = sourceFor(target);
        var id = applicationInState(source);
        appender.list.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            transition(id, target);
            assertThat(appender.list).isEmpty();
        });

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).isEqualTo(
                "Application state changed: id=" + id + " previous=" + source + " new=" + target);
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationState.class, names = {"CREATED", "VERIFIED", "PUBLISHED", "REJECTED", "DELETED"})
    void rolledBackMutationsDoNotLogSuccess(ApplicationState target) throws Exception {
        var id = target == ApplicationState.CREATED ? null : applicationInState(sourceFor(target));
        appender.list.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            if (target == ApplicationState.CREATED) {
                applicationService.create("private-name", "private-body");
            } else {
                transition(id, target);
            }
            transaction.setRollbackOnly();
        });

        assertThat(appender.list).isEmpty();
    }

    @Test
    void rejectedTransitionsLogOnlySafeDebugDetails() throws Exception {
        var id = applicationInState(ApplicationState.REJECTED);
        appender.list.clear();

        assertThatThrownBy(() -> applicationService.reject(id, "private-reason"))
                .isInstanceOf(ApplicationConflictException.class);

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage()).isEqualTo(
                "Application transition rejected: id=" + id + " currentState=REJECTED requestedState=REJECTED");
    }

    private ApplicationState sourceFor(ApplicationState target) {
        return switch (target) {
            case VERIFIED, DELETED -> ApplicationState.CREATED;
            case ACCEPTED, REJECTED -> ApplicationState.VERIFIED;
            case PUBLISHED -> ApplicationState.ACCEPTED;
            default -> throw new IllegalArgumentException("Not a transition target");
        };
    }

    private void transition(UUID id, ApplicationState target) {
        switch (target) {
            case VERIFIED -> applicationService.verify(id);
            case ACCEPTED -> applicationService.accept(id);
            case PUBLISHED -> applicationService.publish(id);
            case REJECTED -> applicationService.reject(id, "private-reason");
            case DELETED -> applicationService.delete(id, "DUPLICATE");
            default -> throw new IllegalArgumentException("Not a transition target");
        }
    }
}
