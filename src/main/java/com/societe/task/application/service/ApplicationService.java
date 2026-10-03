package com.societe.task.application.service;

import java.util.UUID;

import com.societe.task.application.domain.Application;
import com.societe.task.application.domain.ApplicationState;
import com.societe.task.application.domain.exception.ApplicationConflictException;
import com.societe.task.application.domain.exception.ApplicationNotFoundException;
import com.societe.task.application.persistence.JdbcApplicationRepository;
import com.societe.task.application.persistence.JdbcApplicationStateHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ApplicationService {

    private final JdbcApplicationRepository repository;
    private final JdbcApplicationStateHistoryRepository historyRepository;

    public ApplicationService(JdbcApplicationRepository repository, JdbcApplicationStateHistoryRepository historyRepository) {
        this.repository = repository;
        this.historyRepository = historyRepository;
    }

    public Application create(String name, String body) {
        var application = repository.create(UUID.randomUUID(), name, body);
        historyRepository.record(application.id(), null, application.state(), null);
        return application;
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
        var application = requireTransition(id, ApplicationState.PUBLISHED);
        var published = repository.publish(id);
        historyRepository.record(id, application.state(), published.state(), null);
        return published;
    }

    public Application reject(UUID id, String reason) {
        var application = requireTransition(id, ApplicationState.REJECTED);
        var rejected = repository.reject(id, application.state(), reason);
        historyRepository.record(id, application.state(), rejected.state(), reason);
        return rejected;
    }

    public void delete(UUID id, String reason) {
        var application = requireTransition(id, ApplicationState.DELETED);
        repository.softDelete(id, reason);
        historyRepository.record(id, application.state(), ApplicationState.DELETED, reason);
    }

    private Application changeState(UUID id, ApplicationState target) {
        var application = requireTransition(id, target);
        var changed = repository.changeState(id, application.state(), target);
        historyRepository.record(id, application.state(), changed.state(), null);
        return changed;
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
