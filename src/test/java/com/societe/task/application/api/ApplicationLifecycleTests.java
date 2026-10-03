package com.societe.task.application.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.societe.task.application.domain.ApplicationState;
import com.societe.task.application.support.ApplicationApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationLifecycleTests extends ApplicationApiTestSupport {

    @Test
    void createsAnApplicationWithUuidAndPersistsIt() throws Exception {
        var application = createApplication();
        var id = UUID.fromString(application.get("id").asText());

        assertThat(application.get("name").asText()).isEqualTo("My application");
        assertThat(application.get("body").asText()).isEqualTo("Initial body");
        assertThat(application.get("state").asText()).isEqualTo("CREATED");
        assertThat(application.get("publicationNumber").isNull()).isTrue();
        assertThat(application.get("rejectionReason").isNull()).isTrue();
        assertThat(application.get("rejectedAt").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(application.get("createdAt").asText())).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM applications WHERE id = ?", String.class, id))
                .isEqualTo("My application");
        var history = stateHistory(id);
        assertThat(history).hasSize(1);
        var creation = history.getFirst();
        assertThat((Long) creation.get("id")).isPositive();
        assertThat(creation).containsEntry("application_id", id)
                .containsEntry("previous_state", null).containsEntry("new_state", "CREATED")
                .containsEntry("reason", null)
                .containsEntry("changed_at", storedApplication(id).get("created_at"));
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationState.class, names = {"CREATED", "VERIFIED"})
    void editsOnlyBodyAndPreservesNameAndState(ApplicationState state) throws Exception {
        var id = applicationInState(state);
        var historyBefore = stateHistory(id);

        mockMvc.perform(patch("/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Updated body\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("My application"))
                .andExpect(jsonPath("$.state").value(state.name()))
                .andExpect(jsonPath("$.body").value("Updated body"));

        assertThat(jdbcTemplate.queryForObject("SELECT body FROM applications WHERE id = ?", String.class, id))
                .isEqualTo("Updated body");
        assertThat(stateHistory(id)).isEqualTo(historyBefore);
    }

    @Test
    void rejectsWithReasonAndRepeatedPutReturnsConflictWithoutChangingData() throws Exception {
        var id = applicationInState(ApplicationState.VERIFIED);
        var request = "{\"reason\":\"Missing documents\"}";

        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Missing documents"))
                .andExpect(jsonPath("$.rejectedAt").isString());

        var stored = storedApplication(id);
        var history = stateHistory(id);

        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already been changed")));
        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not eligible\"}"))
                .andExpect(status().isConflict());

        assertThat(storedApplication(id)).isEqualTo(stored);
        assertThat(stateHistory(id)).isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE", "CREATED_BY_MISTAKE", "NO_LONGER_NEEDED"})
    void softDeletesWithEachSupportedReason(String reason) throws Exception {
        var id = createApplication().get("id").asText();
        mockMvc.perform(delete("/applications/{id}", id).param("reason", reason))
                .andExpect(status().isNoContent()).andExpect(content().string(""));

        var stored = storedApplication(UUID.fromString(id));
        assertThat(stored.get("deletion_reason")).isEqualTo(reason);
        assertThat(stored.get("deleted_at")).isNotNull();
        assertThat(stored.get("state")).isEqualTo("DELETED");
        assertThat(stored.get("name")).isEqualTo("My application");
        assertThat(stored.get("body")).isEqualTo("Initial body");
        var history = stateHistory(UUID.fromString(id));
        assertThat(history).hasSize(2);
        assertThat(history.getLast()).containsEntry("previous_state", "CREATED")
                .containsEntry("new_state", "DELETED").containsEntry("reason", reason)
                .containsEntry("changed_at", stored.get("deleted_at"));
    }

    @Test
    void repeatedDeletionPreservesOriginalReasonAndTimestamp() throws Exception {
        var id = applicationInState(ApplicationState.DELETED);
        var before = storedApplication(id);
        var historyBefore = stateHistory(id);
        for (var reason : List.of("DUPLICATE", "NO_LONGER_NEEDED")) {
            mockMvc.perform(delete("/applications/{id}", id).param("reason", reason))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("already been changed")));
            assertThat(storedApplication(id)).isEqualTo(before);
            assertThat(stateHistory(id)).isEqualTo(historyBefore);
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("transitionCases")
    void enforcesRepresentativeTransitionsThroughApi(ApplicationState source, ApplicationState target, boolean allowed) throws Exception {
        var id = applicationInState(source);
        var before = storedApplication(id);
        var historyBefore = stateHistory(id);
        var response = performTransition(id, target);

        if (allowed) {
            response.andExpect(status().is(target == ApplicationState.DELETED ? 204 : 200));
            if (target != ApplicationState.DELETED) {
                response.andExpect(jsonPath("$.state").value(target.name()));
            }
            var after = storedApplication(id);
            var history = stateHistory(id);
            assertThat(history).hasSize(historyBefore.size() + 1);
            assertThat(history.subList(0, historyBefore.size())).isEqualTo(historyBefore);
            var expectedReason = switch (target) {
                case REJECTED -> "Missing documents";
                case DELETED -> "DUPLICATE";
                default -> null;
            };
            assertThat(history.getLast()).containsEntry("application_id", id)
                    .containsEntry("previous_state", source.name()).containsEntry("new_state", target.name())
                    .containsEntry("reason", expectedReason).containsEntry("changed_at", after.get("updated_at"));
            assertThat(after.get("state")).isEqualTo(target.name());
            assertThat(after.get("name")).isEqualTo(before.get("name"));
            assertThat(after.get("body")).isEqualTo(before.get("body"));
            if (target == ApplicationState.PUBLISHED) {
                assertThat((Long) after.get("publication_number")).isPositive();
            } else {
                assertThat(after.get("publication_number")).isNull();
            }
            if (target == ApplicationState.REJECTED) {
                assertThat(after.get("rejection_reason")).isEqualTo("Missing documents");
                assertThat(after.get("rejected_at")).isNotNull();
            }
            if (target == ApplicationState.DELETED) {
                assertThat(after.get("deletion_reason")).isEqualTo("DUPLICATE");
                assertThat(after.get("deleted_at")).isNotNull();
            }
        } else {
            response.andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.title").value("Conflict"))
                    .andExpect(jsonPath("$.detail").value(containsString("current state is " + source)));
            assertThat(storedApplication(id)).isEqualTo(before);
            assertThat(stateHistory(id)).isEqualTo(historyBefore);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationState.class, names = {"ACCEPTED", "PUBLISHED", "REJECTED", "DELETED"})
    void cannotEditBodyInOtherStates(ApplicationState state) throws Exception {
        var id = applicationInState(state);
        var before = storedApplication(id);
        var expectedStatus = state == ApplicationState.DELETED ? 404 : 409;
        mockMvc.perform(patch("/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Changed\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus));
        assertThat(storedApplication(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationState.class, names = {"VERIFIED", "DELETED"})
    void concurrentRequestsCannotRepeatOrBypassTransitions(ApplicationState competingTarget) throws Exception {
        var id = applicationInState(ApplicationState.CREATED);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var verification = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return performTransition(id, ApplicationState.VERIFIED).andReturn().getResponse().getStatus();
            });
            var competing = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return performTransition(id, competingTarget).andReturn().getResponse().getStatus();
            });
            var verificationStatus = verification.get(15, TimeUnit.SECONDS);
            var competingStatus = competing.get(15, TimeUnit.SECONDS);
            var expectedSuccess = competingTarget == ApplicationState.DELETED && competingStatus == 204 ? 204 : 200;
            assertThat(List.of(verificationStatus, competingStatus)).containsExactlyInAnyOrder(expectedSuccess, 409);

            var stored = storedApplication(id);
            var expectedState = competingStatus == 204 ? "DELETED" : "VERIFIED";
            assertThat(stored.get("state")).isEqualTo(expectedState);
            assertThat(stored.get("name")).isEqualTo("My application");
            assertThat(stored.get("body")).isEqualTo("Initial body");
            assertThat(stored.get("deleted_at") != null).isEqualTo(expectedState.equals("DELETED"));
            var history = stateHistory(id);
            assertThat(history).hasSize(2);
            assertThat(history.getLast()).containsEntry("previous_state", "CREATED")
                    .containsEntry("new_state", expectedState);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"name\":\"My application\"}", "{\"body\":\"Body\"}",
            "{\"name\":\"  \",\"body\":\"Body\"}", "{\"name\":\"Name\",\"body\":\"  \"}",
            "{\"name\":null,\"body\":\"Body\"}", "{\"name\":\"Name\",\"body\":null}",
            "{\"name\":\"Name\",\"body\":\"Body\",\"unknown\":true}",
            "{\"name\":\"Name\",\"body\":\"Body\",\"state\":\"PUBLISHED\"}",
            "{\"name\":\"Name\",\"body\":\"Body\",\"publicationNumber\":123}"
    })
    void rejectsInvalidCreateInput(String request) throws Exception {
        mockMvc.perform(post("/applications").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM applications", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM application_state_history", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"body\":null}", "{\"body\":\"\"}", "{\"body\":\"  \"}",
            "{\"body\":\"Updated body\",\"name\":\"Changed name\"}",
            "{\"body\":\"Updated body\",\"state\":\"ACCEPTED\"}",
            "{\"body\":\"Updated body\",\"publicationNumber\":123}"})
    void rejectsInvalidEditOrAttemptToChangeName(String request) throws Exception {
        var id = createApplication().get("id").asText();
        mockMvc.perform(patch("/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        var stored = jdbcTemplate.queryForMap("SELECT name, body FROM applications WHERE id = ?", UUID.fromString(id));
        assertThat(stored).containsEntry("name", "My application").containsEntry("body", "Initial body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"  \"}"})
    void rejectsMissingOrBlankRejectionReason(String request) throws Exception {
        var id = applicationInState(ApplicationState.VERIFIED);
        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT rejection_reason FROM applications WHERE id = ?",
                String.class, id)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "OTHER", "duplicate"})
    void rejectsUnsupportedDeletionReason(String reason) throws Exception {
        var id = createApplication().get("id").asText();
        mockMvc.perform(delete("/applications/{id}", id).param("reason", reason))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM applications WHERE id = ?",
                OffsetDateTime.class, UUID.fromString(id))).isNull();
    }

    @Test
    void deletionReasonIsMandatory() throws Exception {
        var id = createApplication().get("id").asText();
        mockMvc.perform(delete("/applications/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unknownApplicationsReturnNotFound() throws Exception {
        var id = UUID.randomUUID();
        mockMvc.perform(patch("/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Updated body\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Application not found: " + id))
                .andExpect(jsonPath("$.instance").value("/applications/" + id));
        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing documents\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/applications/{id}", id).param("reason", "DUPLICATE"))
                .andExpect(status().isNotFound());
        for (var action : List.of("verification", "acceptance", "publication")) {
            mockMvc.perform(put("/applications/{id}/" + action, id)).andExpect(status().isNotFound());
        }
        assertThat(stateHistory(id)).isEmpty();
    }

    @Test
    void invalidUuidReturnsBadRequest() throws Exception {
        mockMvc.perform(delete("/applications/not-a-uuid").param("reason", "DUPLICATE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsMultilineBody() throws Exception {
        var request = objectMapper.writeValueAsString(Map.of("name", "Multiline", "body", "First line\nSecond line"));
        mockMvc.perform(post("/applications").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("First line\nSecond line"));
    }

    private static Stream<Arguments> transitionCases() {
        // Exhaustive policy coverage belongs to ApplicationStateTests. Here we verify
        // every successful endpoint path, shared retry handling, and representative errors.
        // Dedicated tests cover publication terminality and rejection/deletion retries.
        return Stream.of(
                Arguments.of(ApplicationState.CREATED, ApplicationState.VERIFIED, true),
                Arguments.of(ApplicationState.CREATED, ApplicationState.DELETED, true),
                Arguments.of(ApplicationState.VERIFIED, ApplicationState.ACCEPTED, true),
                Arguments.of(ApplicationState.VERIFIED, ApplicationState.REJECTED, true),
                Arguments.of(ApplicationState.ACCEPTED, ApplicationState.PUBLISHED, true),
                Arguments.of(ApplicationState.ACCEPTED, ApplicationState.REJECTED, true),
                Arguments.of(ApplicationState.VERIFIED, ApplicationState.VERIFIED, false),
                Arguments.of(ApplicationState.ACCEPTED, ApplicationState.ACCEPTED, false),
                Arguments.of(ApplicationState.CREATED, ApplicationState.ACCEPTED, false),
                Arguments.of(ApplicationState.CREATED, ApplicationState.PUBLISHED, false),
                Arguments.of(ApplicationState.CREATED, ApplicationState.REJECTED, false),
                Arguments.of(ApplicationState.VERIFIED, ApplicationState.DELETED, false),
                Arguments.of(ApplicationState.ACCEPTED, ApplicationState.DELETED, false),
                Arguments.of(ApplicationState.DELETED, ApplicationState.REJECTED, false),
                Arguments.of(ApplicationState.REJECTED, ApplicationState.VERIFIED, false));
    }
}
