package com.societe.task.application.service;

import java.util.List;

import com.societe.task.application.domain.Application;

public record ApplicationPage(
        List<Application> content,
        int page,
        int size,
        long totalElements,
        long totalPages) {
}
