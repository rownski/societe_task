package com.societe.task.application;

public enum ApplicationState {
    CREATED,
    VERIFIED,
    ACCEPTED,
    PUBLISHED,
    DELETED,
    REJECTED;

    public boolean canTransitionTo(ApplicationState target) {
        return switch (this) {
            case CREATED -> target == VERIFIED || target == DELETED;
            case VERIFIED -> target == ACCEPTED || target == REJECTED;
            case ACCEPTED -> target == PUBLISHED || target == REJECTED;
            case PUBLISHED, DELETED, REJECTED -> false;
        };
    }

    public boolean canEditBody() {
        return this == CREATED || this == VERIFIED;
    }
}
