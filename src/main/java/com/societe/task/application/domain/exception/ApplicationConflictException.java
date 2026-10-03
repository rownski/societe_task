package com.societe.task.application.domain.exception;

public class ApplicationConflictException extends RuntimeException {

    public ApplicationConflictException(String message) {
        super(message);
    }
}
