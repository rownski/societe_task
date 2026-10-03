package com.societe.task.application.service;

import java.util.UUID;

import com.societe.task.application.domain.Application;
import com.societe.task.application.domain.ApplicationState;
import com.societe.task.application.domain.exception.ApplicationConflictException;
import com.societe.task.application.domain.exception.ApplicationNotFoundException;
import com.societe.task.application.persistence.JdbcApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ApplicationService {

    private final JdbcApplicationRepository repository;

    public ApplicationService(JdbcApplicationRepository repository) {
        this.repository = repository;
    }

    public Application create(String name, String body) {
        return repository.create(UUID.randomUUID(), name, body);
    }

    // Count and content use one snapshot, even while other requests create or delete rows.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationPage list(String name, ApplicationState state, int page, int size) {
        var totalElements = repository.count(name, state);
        var content = repository.list(name, state, page, size);
        var totalPages = totalElements / size + (totalElements % size == 0 ? 0 : 1);
        return new ApplicationPage(content, page, size, totalElements, totalPages);
    }

    public Application edit(UUID id, String body) {
        var application = findForUpdate(id);
        if (application.state() == ApplicationState.DELETED) {
            throw new ApplicationNotFoundException(id);
        }
        if (!application.state().canEditBody()) {
            throw new ApplicationConflictException(
                    "Application body cannot be edited; its current state is " + application.state() + ".");
        }
        return repository.updateBody(id, body);
    }

    public Application verify(UUID id) {
        return changeState(id, ApplicationState.VERIFIED);
    }

    public Application accept(UUID id) {
        return changeState(id, ApplicationState.ACCEPTED);
    }

    public Application publish(UUID id) {
        requireTransition(id, ApplicationState.PUBLISHED);
        return repository.publish(id);
    }

    public Application reject(UUID id, String reason) {
        var application = requireTransition(id, ApplicationState.REJECTED);
        return repository.reject(id, application.state(), reason);
    }

    public void delete(UUID id, String reason) {
        requireTransition(id, ApplicationState.DELETED);
        repository.softDelete(id, reason);
    }

    private Application changeState(UUID id, ApplicationState target) {
        var application = requireTransition(id, target);
        return repository.changeState(id, application.state(), target);
    }

    private Application requireTransition(UUID id, ApplicationState target) {
        var application = findForUpdate(id);
        if (!application.state().canTransitionTo(target)) {
            throw new ApplicationConflictException(
                    "Application has already been changed or the transition is not allowed; its current state is "
                            + application.state() + ". Requested state: " + target + ".");
        }
        return application;
    }

    private Application findForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new ApplicationNotFoundException(id));
    }
}
