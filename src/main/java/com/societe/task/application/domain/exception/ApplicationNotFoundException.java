package com.societe.task.application.domain.exception;

import java.util.UUID;

public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException(UUID id) {
        super("Application not found: " + id);
    }
}
