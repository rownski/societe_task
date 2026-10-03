package com.societe.task.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Application(
        UUID id,
        String name,
        String body,
        ApplicationState state,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String rejectionReason,
        OffsetDateTime rejectedAt,
        String deletionReason,
        OffsetDateTime deletedAt) {
}
