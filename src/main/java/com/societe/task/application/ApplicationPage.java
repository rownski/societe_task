package com.societe.task.application;

import java.util.List;

public record ApplicationPage(
        List<Application> content,
        int page,
        int size,
        long totalElements,
        long totalPages) {
}
