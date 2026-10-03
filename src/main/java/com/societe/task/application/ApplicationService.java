package com.societe.task.application;

import java.util.Objects;
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
        requireActive(id);
        return repository.updateBody(id, body);
    }

    public Application reject(UUID id, String reason) {
        var application = requireActive(id);
        if (Objects.equals(application.rejectionReason(), reason)) {
            return application;
        }
        return repository.reject(id, reason);
    }

    public void delete(UUID id, String reason) {
        var application = findForUpdate(id);
        if (application.deletedAt() == null) {
            repository.softDelete(id, reason);
        }
    }

    private Application requireActive(UUID id) {
        var application = findForUpdate(id);
        if (application.deletedAt() != null) {
            throw notFound(id);
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
