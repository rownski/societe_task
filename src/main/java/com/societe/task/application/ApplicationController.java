package com.societe.task.application;

import java.util.UUID;

import com.societe.task.api.ApplicationsApi;
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
    public ResponseEntity<ApplicationResponse> createApplication(CreateApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(service.create(request.getName(), request.getBody())));
    }

    @Override
    public ResponseEntity<ApplicationResponse> editApplication(UUID id, EditApplicationRequest request) {
        return ResponseEntity.ok(toResponse(service.edit(id, request.getBody())));
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
        return new ApplicationResponse(application.id(), application.name(), application.body(),
                application.createdAt(), application.updatedAt())
                .rejectionReason(application.rejectionReason())
                .rejectedAt(application.rejectedAt());
    }
}
