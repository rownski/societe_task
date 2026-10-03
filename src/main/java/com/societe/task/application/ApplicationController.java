package com.societe.task.application;

import java.util.UUID;

import com.societe.task.api.ApplicationsApi;
import com.societe.task.api.model.ApplicationPageResponse;
import com.societe.task.api.model.ApplicationResponse;
import com.societe.task.api.model.CreateApplicationRequest;
import com.societe.task.api.model.DeletionReason;
import com.societe.task.api.model.EditApplicationRequest;
import com.societe.task.api.model.RejectApplicationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationController implements ApplicationsApi {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<ApplicationPageResponse> listApplications(
            String name, com.societe.task.api.model.ApplicationState state, Integer page, Integer size) {
        var result = service.list(name, state == null ? null : ApplicationState.valueOf(state.getValue()), page, size);
        return ResponseEntity.ok(new ApplicationPageResponse()
                .content(result.content().stream().map(this::toResponse).toList())
                .page(result.page())
                .size(result.size())
                .totalElements(result.totalElements())
                .totalPages(result.totalPages()));
    }

    @Override
    public ResponseEntity<ApplicationResponse> createApplication(CreateApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(service.create(request.getName(), request.getBody())));
    }

    @Override
    public ResponseEntity<ApplicationResponse> editApplication(UUID id, EditApplicationRequest request) {
        return ResponseEntity.ok(toResponse(service.edit(id, request.getBody())));
    }

    @Override
    public ResponseEntity<ApplicationResponse> verifyApplication(UUID id) {
        return ResponseEntity.ok(toResponse(service.verify(id)));
    }

    @Override
    public ResponseEntity<ApplicationResponse> acceptApplication(UUID id) {
        return ResponseEntity.ok(toResponse(service.accept(id)));
    }

    @Override
    public ResponseEntity<ApplicationResponse> publishApplication(UUID id) {
        return ResponseEntity.ok(toResponse(service.publish(id)));
    }

    @Override
    public ResponseEntity<ApplicationResponse> rejectApplication(UUID id, RejectApplicationRequest request) {
        return ResponseEntity.ok(toResponse(service.reject(id, request.getReason())));
    }

    @Override
    public ResponseEntity<Void> deleteApplication(UUID id, DeletionReason reason) {
        service.delete(id, reason.getValue());
        return ResponseEntity.noContent().build();
    }

    private ApplicationResponse toResponse(Application application) {
        return new ApplicationResponse()
                .id(application.id())
                .name(application.name())
                .body(application.body())
                .state(com.societe.task.api.model.ApplicationState.fromValue(application.state().name()))
                .publicationNumber(application.publicationNumber())
                .createdAt(application.createdAt())
                .updatedAt(application.updatedAt())
                .rejectionReason(application.rejectionReason())
                .rejectedAt(application.rejectedAt());
    }
}
