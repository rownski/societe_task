package com.societe.task.application.api;

import java.util.UUID;

import com.societe.task.application.api.dto.ApplicationPageResponse;
import com.societe.task.application.api.dto.ApplicationResponse;
import com.societe.task.application.api.dto.ApplicationState;
import com.societe.task.application.api.dto.CreateApplicationRequest;
import com.societe.task.application.api.dto.DeletionReason;
import com.societe.task.application.api.dto.EditApplicationRequest;
import com.societe.task.application.api.dto.RejectApplicationRequest;
import com.societe.task.application.service.ApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationController implements ApplicationsApi {

    private final ApplicationService service;
    private final ApplicationMapper mapper;

    public ApplicationController(ApplicationService service, ApplicationMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<ApplicationPageResponse> listApplications(
            String name, ApplicationState state, Integer page, Integer size) {
        var result = service.list(name, mapper.toDomainState(state), page, size);
        return ResponseEntity.ok(mapper.toResponse(result));
    }

    @Override
    public ResponseEntity<ApplicationResponse> createApplication(CreateApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.create(request.getName(), request.getBody())));
    }

    @Override
    public ResponseEntity<ApplicationResponse> editApplication(UUID id, EditApplicationRequest request) {
        return ResponseEntity.ok(mapper.toResponse(service.edit(id, request.getBody())));
    }

    @Override
    public ResponseEntity<ApplicationResponse> verifyApplication(UUID id) {
        return ResponseEntity.ok(mapper.toResponse(service.verify(id)));
    }

    @Override
    public ResponseEntity<ApplicationResponse> acceptApplication(UUID id) {
        return ResponseEntity.ok(mapper.toResponse(service.accept(id)));
    }

    @Override
    public ResponseEntity<ApplicationResponse> publishApplication(UUID id) {
        return ResponseEntity.ok(mapper.toResponse(service.publish(id)));
    }

    @Override
    public ResponseEntity<ApplicationResponse> rejectApplication(UUID id, RejectApplicationRequest request) {
        return ResponseEntity.ok(mapper.toResponse(service.reject(id, request.getReason())));
    }

    @Override
    public ResponseEntity<Void> deleteApplication(UUID id, DeletionReason reason) {
        service.delete(id, reason.getValue());
        return ResponseEntity.noContent().build();
    }
}
