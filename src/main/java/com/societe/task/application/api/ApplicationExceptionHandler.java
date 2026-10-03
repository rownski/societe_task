package com.societe.task.application.api;

import com.societe.task.application.domain.exception.ApplicationConflictException;
import com.societe.task.application.domain.exception.ApplicationNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApplicationExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplicationExceptionHandler.class);

    @ExceptionHandler(ApplicationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ApplicationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage()));
    }

    @ExceptionHandler(ApplicationConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ApplicationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
    }

    // Inherited MVC handlers retain normal 400/405/415 problem responses.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected application request failure: method={}", request.getMethod(), diagnostic(exception, 0));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(unexpectedProblem(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            var method = request instanceof ServletWebRequest servletRequest
                    ? servletRequest.getRequest().getMethod() : "unknown";
            log.error("Unexpected application request failure: method={}", method, diagnostic(exception, 0));
            body = unexpectedProblem(status);
        }
        return super.handleExceptionInternal(exception, body, headers, status, request);
    }

    private ProblemDetail unexpectedProblem(HttpStatusCode status) {
        return ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred.");
    }

    private RuntimeException diagnostic(Throwable exception, int depth) {
        // JDBC/validation exception messages can contain entire rows or request values.
        // Keep exception types and stack frames, but omit messages and suppressed exceptions.
        var cause = exception.getCause() != null && depth < 5 ? diagnostic(exception.getCause(), depth + 1) : null;
        var diagnostic = new RuntimeException(exception.getClass().getName(), cause);
        diagnostic.setStackTrace(exception.getStackTrace());
        return diagnostic;
    }
}
