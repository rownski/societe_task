package com.societe.task.application.api;

import com.societe.task.application.api.dto.ApplicationPageResponse;
import com.societe.task.application.api.dto.ApplicationResponse;
import com.societe.task.application.api.dto.ApplicationState;
import com.societe.task.application.domain.Application;
import com.societe.task.application.service.ApplicationPage;
import org.springframework.stereotype.Component;

@Component
public class ApplicationMapper {

    public ApplicationResponse toResponse(Application application) {
        return new ApplicationResponse()
                .id(application.id())
                .name(application.name())
                .body(application.body())
                .state(ApplicationState.fromValue(application.state().name()))
                .publicationNumber(application.publicationNumber())
                .createdAt(application.createdAt())
                .updatedAt(application.updatedAt())
                .rejectionReason(application.rejectionReason())
                .rejectedAt(application.rejectedAt());
    }

    public ApplicationPageResponse toResponse(ApplicationPage page) {
        return new ApplicationPageResponse()
                .content(page.content().stream().map(this::toResponse).toList())
                .page(page.page())
                .size(page.size())
                .totalElements(page.totalElements())
                .totalPages(page.totalPages());
    }

    public com.societe.task.application.domain.ApplicationState toDomainState(ApplicationState state) {
        return state == null ? null : com.societe.task.application.domain.ApplicationState.valueOf(state.getValue());
    }
}
