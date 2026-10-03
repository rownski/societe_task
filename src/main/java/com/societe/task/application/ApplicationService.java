package com.societe.task.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ApplicationService {

    private final ApplicationRepository repository;

    public ApplicationService(ApplicationRepository repository) {
        this.repository = repository;
    }

    public Application create(String name, String body) {
        return repository.create(UUID.randomUUID(), name, body);
    }

    public Application edit(UUID id, String body) {
        var application = findForUpdate(id);
        if (application.state() == ApplicationState.DELETED) {
            throw notFound(id);
        }
        if (!application.state().canEditBody()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
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
        return changeState(id, ApplicationState.PUBLISHED);
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
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Application has already been changed or the transition is not allowed; its current state is "
                            + application.state() + ". Requested state: " + target + ".");
        }
        return application;
    }

    private Application findForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
    }

    private ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found: " + id);
    }
}
