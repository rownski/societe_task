package com.societe.task.application.api;

import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.societe.task.application.domain.exception.ApplicationConflictException;
import com.societe.task.application.domain.exception.ApplicationNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationExceptionHandlerTests {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApplicationExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApplicationExceptionHandler()).build();
    }

    @AfterEach
    void removeAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unexpected", "serialization"})
    void unexpectedErrorsHaveSafeProblemResponsesAndDiagnosticLogs(String endpoint) throws Exception {
        mockMvc.perform(get("/logging-test/" + endpoint))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo("Unexpected application request failure: method=GET");
        var diagnostic = ThrowableProxyUtil.asString(event.getThrowableProxy());
        assertThat(diagnostic).contains("FailingController", "java.lang.IllegalArgumentException")
                .doesNotContain("private-body", "private-reason", "private-credential");
    }

    @Test
    void expectedBusinessErrorsAreNotLoggedAsUnexpectedFailures() throws Exception {
        mockMvc.perform(get("/logging-test/conflict")).andExpect(status().isConflict());
        mockMvc.perform(get("/logging-test/missing")).andExpect(status().isNotFound());
        assertThat(appender.list).isEmpty();
    }

    @Test
    void invalidClientRequestsKeepTheirNormalStatuses() throws Exception {
        mockMvc.perform(get("/logging-test/number").param("value", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mockMvc.perform(put("/logging-test/number"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405));
        mockMvc.perform(post("/logging-test/json").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value(415));
        assertThat(appender.list).isEmpty();
    }

    @RestController
    class FailingController {

        @GetMapping("/logging-test/unexpected")
        String unexpected() {
            var exception = new IllegalStateException("private-body", new IllegalArgumentException("private-reason"));
            exception.addSuppressed(new IllegalStateException("private-credential"));
            throw exception;
        }

        @GetMapping("/logging-test/serialization")
        String serialization() {
            throw new HttpMessageNotWritableException("private-body", new IllegalArgumentException("private-reason"));
        }

        @GetMapping("/logging-test/conflict")
        String conflict() {
            throw new ApplicationConflictException("Transition not allowed");
        }

        @GetMapping("/logging-test/missing")
        String missing() {
            throw new ApplicationNotFoundException(UUID.randomUUID());
        }

        @GetMapping("/logging-test/number")
        int number(@RequestParam("value") int value) {
            return value;
        }

        @PostMapping(value = "/logging-test/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        String json() {
            return "ok";
        }
    }
}
