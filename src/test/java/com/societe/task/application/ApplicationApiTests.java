package com.societe.task.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationApiTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanApplications() {
        jdbcTemplate.update("DELETE FROM applications");
    }

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
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationState.class, names = {"CREATED", "VERIFIED"})
    void editsOnlyBodyAndPreservesNameAndState(ApplicationState state) throws Exception {
        var id = applicationInState(state);

        mockMvc.perform(patch("/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Updated body\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("My application"))
                .andExpect(jsonPath("$.state").value(state.name()))
                .andExpect(jsonPath("$.body").value("Updated body"));

        assertThat(jdbcTemplate.queryForObject("SELECT body FROM applications WHERE id = ?", String.class,
                id)).isEqualTo("Updated body");
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

        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already been changed")));

        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not eligible\"}"))
                .andExpect(status().isConflict());

        assertThat(storedApplication(id)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE", "CREATED_BY_MISTAKE", "NO_LONGER_NEEDED"})
    void softDeletesWithEachSupportedReason(String reason) throws Exception {
        var id = createApplication().get("id").asText();

        mockMvc.perform(delete("/applications/{id}", id).param("reason", reason))
                .andExpect(status().isNoContent()).andExpect(content().string(""));

        var stored = jdbcTemplate.queryForMap("SELECT * FROM applications WHERE id = ?", UUID.fromString(id));
        assertThat(stored.get("deletion_reason")).isEqualTo(reason);
        assertThat(stored.get("deleted_at")).isNotNull();
        assertThat(stored.get("state")).isEqualTo("DELETED");
        assertThat(stored.get("name")).isEqualTo("My application");
        assertThat(stored.get("body")).isEqualTo("Initial body");
    }

    @Test
    void repeatedDeletionPreservesOriginalReasonAndTimestamp() throws Exception {
        var id = applicationInState(ApplicationState.DELETED);
        var before = storedApplication(id);
        for (var reason : List.of("DUPLICATE", "NO_LONGER_NEEDED")) {
            mockMvc.perform(delete("/applications/{id}", id).param("reason", reason))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("already been changed")));
            assertThat(storedApplication(id)).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("transitionCases")
    void enforcesRepresentativeTransitionsThroughApi(ApplicationState source, ApplicationState target, boolean allowed) throws Exception {
        var id = applicationInState(source);
        var before = storedApplication(id);
        var response = performTransition(id, target);

        if (allowed) {
            response.andExpect(status().is(target == ApplicationState.DELETED ? 204 : 200));
            if (target != ApplicationState.DELETED) {
                response.andExpect(jsonPath("$.state").value(target.name()));
            }
            var after = storedApplication(id);
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
                    .andExpect(jsonPath("$.detail").value(containsString("current state is " + source)));
            assertThat(storedApplication(id)).isEqualTo(before);
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
        }
    }

    @Test
    void publicationAssignsNumberExposesItInListingAndKeepsTheApplicationFinal() throws Exception {
        var id = applicationInState(ApplicationState.ACCEPTED);
        assertThat(storedApplication(id).get("publication_number")).isNull();

        var response = performTransition(id, ApplicationState.PUBLISHED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.state").value("PUBLISHED"))
                .andExpect(jsonPath("$.publicationNumber").isNumber())
                .andReturn().getResponse().getContentAsString();
        var number = objectMapper.readTree(response).get("publicationNumber").longValue();
        assertThat(number).isPositive();
        var published = storedApplication(id);
        assertThat(published).containsEntry("publication_number", number);

        mockMvc.perform(get("/applications").param("state", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].publicationNumber").value(number));

        var sequenceBefore = publicationSequenceState();
        for (var target : ApplicationState.values()) {
            if (target != ApplicationState.CREATED) {
                performTransition(id, target)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.detail").value(containsString("current state is PUBLISHED")));
            }
        }
        assertThat(storedApplication(id)).isEqualTo(published);
        assertThat(publicationSequenceState()).isEqualTo(sequenceBefore);
    }

    @Test
    void rollingBackPublicationKeepsStateAndNumberUnchanged() throws Exception {
        var id = applicationInState(ApplicationState.ACCEPTED);
        var before = storedApplication(id);
        var rolledBackPublication = new TransactionTemplate(transactionManager).execute(transaction -> {
            var published = applicationService.publish(id);
            transaction.setRollbackOnly();
            return published;
        });

        assertThat(rolledBackPublication.state()).isEqualTo(ApplicationState.PUBLISHED);
        assertThat(rolledBackPublication.publicationNumber()).isPositive();
        assertThat(storedApplication(id)).isEqualTo(before);

        var response = performTransition(id, ApplicationState.PUBLISHED).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var number = objectMapper.readTree(response).get("publicationNumber").longValue();
        // Sequence values are not rolled back; gaps are allowed, but partial publication is not.
        assertThat(number).isGreaterThan(rolledBackPublication.publicationNumber());
        assertThat(storedApplication(id)).containsEntry("publication_number", number).containsEntry("state", "PUBLISHED");
    }

    @ParameterizedTest(name = "same application: {0}")
    @ValueSource(booleans = {true, false})
    void concurrentPublicationsAllocateUniqueNumbersOnlyForSuccessfulRequests(boolean sameApplication) throws Exception {
        var firstId = applicationInState(ApplicationState.ACCEPTED);
        var secondId = sameApplication ? firstId : applicationInState(ApplicationState.ACCEPTED);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return performTransition(firstId, ApplicationState.PUBLISHED).andReturn().getResponse();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return performTransition(secondId, ApplicationState.PUBLISHED).andReturn().getResponse();
            });
            var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(responses.stream().map(response -> response.getStatus()).toList())
                    .containsExactlyInAnyOrder(200, sameApplication ? 409 : 200);
            var numbers = new ArrayList<Long>();
            for (var response : responses) {
                if (response.getStatus() == 200) {
                    var application = objectMapper.readTree(response.getContentAsString());
                    var number = application.get("publicationNumber").longValue();
                    assertThat(number).isPositive();
                    numbers.add(number);
                    var id = UUID.fromString(application.get("id").asText());
                    assertThat(storedApplication(id)).containsEntry("state", "PUBLISHED")
                            .containsEntry("publication_number", number);
                }
            }
            assertThat(numbers).hasSize(sameApplication ? 1 : 2).doesNotHaveDuplicates();
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
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing documents\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/applications/{id}", id).param("reason", "DUPLICATE"))
                .andExpect(status().isNotFound());
        for (var action : List.of("verification", "acceptance", "publication")) {
            mockMvc.perform(put("/applications/{id}/" + action, id)).andExpect(status().isNotFound());
        }
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

    @Test
    void listsWithDefaultPageSizeAndExcludesDeletedApplications() throws Exception {
        for (var index = 0; index < 12; index++) {
            listingApplication("Application " + index, ApplicationState.CREATED);
        }
        var deleted = listingApplication("Deleted application", ApplicationState.DELETED);

        var response = mockMvc.perform(get("/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(10))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(response).get("content").findValuesAsText("id")).doesNotContain(deleted.toString());

        mockMvc.perform(get("/applications").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void ordersByCreationTimeAndUuidDescendingAcrossPages() throws Exception {
        var earlier = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        var lowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var highId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var newestId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        insertListingFixture(lowId, earlier);
        insertListingFixture(highId, earlier);
        insertListingFixture(newestId, earlier.plusDays(1));

        var expected = List.of(newestId, highId, lowId);
        for (var page = 0; page < expected.size(); page++) {
            mockMvc.perform(get("/applications").param("page", String.valueOf(page)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(expected.get(page).toString()))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(3));
        }
    }

    @ParameterizedTest
    @EnumSource(ApplicationState.class)
    void filtersByExactStateIncludingExplicitlyRequestedDeletedApplications(ApplicationState state) throws Exception {
        UUID expectedId = null;
        for (var fixtureState : ApplicationState.values()) {
            var id = listingApplication("Application in " + fixtureState, fixtureState);
            if (fixtureState == state) {
                expectedId = id;
            }
        }

        mockMvc.perform(get("/applications").param("state", state.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(expectedId.toString()))
                .andExpect(jsonPath("$.content[0].state").value(state.name()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void filtersNamesByCaseInsensitiveSubstringWithoutIncludingDeletedApplications() throws Exception {
        var personal = listingApplication("Personal LOAN request", ApplicationState.CREATED);
        var renewal = listingApplication("loan renewal", ApplicationState.CREATED);
        listingApplication("Mortgage", ApplicationState.CREATED);
        listingApplication("Loan deleted", ApplicationState.DELETED);

        mockMvc.perform(get("/applications").param("name", "LoAn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(personal.toString(), renewal.toString())))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void combinesNameAndStateFiltersWithAnd() throws Exception {
        var expected = listingApplication("Personal LOAN request", ApplicationState.VERIFIED);
        listingApplication("Loan created", ApplicationState.CREATED);
        listingApplication("Mortgage", ApplicationState.VERIFIED);

        mockMvc.perform(get("/applications").param("name", "loan").param("state", "VERIFIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void combinesNameFilterWithExplicitDeletedState() throws Exception {
        var expected = listingApplication("Loan duplicate", ApplicationState.DELETED);
        listingApplication("Loan active", ApplicationState.CREATED);
        listingApplication("Mortgage duplicate", ApplicationState.DELETED);

        mockMvc.perform(get("/applications").param("name", "loan").param("state", "DELETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "\\", "' OR 1=1 --"})
    void treatsNameFilterAsLiteralTextAndNotSqlOrWildcards(String name) throws Exception {
        var expected = listingApplication("Prefix " + name + " suffix", ApplicationState.CREATED);
        listingApplication("Other application", ApplicationState.CREATED);

        mockMvc.perform(get("/applications").param("name", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void blankNameFilterImposesNoRestriction(String name) throws Exception {
        listingApplication("Active", ApplicationState.CREATED);
        listingApplication("Deleted", ApplicationState.DELETED);
        mockMvc.perform(get("/applications").param("name", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void returnsEmptyPageWhenDatabaseIsEmpty() throws Exception {
        mockMvc.perform(get("/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void returnsEmptyPageWhenFiltersHaveNoMatches() throws Exception {
        listingApplication("My application", ApplicationState.CREATED);
        mockMvc.perform(get("/applications").param("name", "Unmatched"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void returnsEmptyOutOfRangePageWithoutOverflowingOffset() throws Exception {
        listingApplication("My application", ApplicationState.CREATED);
        mockMvc.perform(get("/applications").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2147483647))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,abc", "page,2147483648", "size,0", "size,-1", "size,101", "size,abc",
            "state,OTHER", "state,verified"})
    void rejectsInvalidListingParameters(String parameter, String value) throws Exception {
        mockMvc.perform(get("/applications").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    private void insertListingFixture(UUID id, OffsetDateTime createdAt) {
        insertListingFixture(id, "Listing fixture", ApplicationState.CREATED, createdAt);
    }

    private UUID listingApplication(String name, ApplicationState state) {
        var id = UUID.randomUUID();
        insertListingFixture(id, name, state, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private void insertListingFixture(UUID id, String name, ApplicationState state, OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO applications (id, name, body, state, created_at, updated_at,
                    rejection_reason, rejected_at, deletion_reason, deleted_at, publication_number)
                VALUES (?, ?, 'Initial body', ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'PUBLISHED' THEN nextval('application_publication_number_seq') ELSE NULL END)
                """, id, name, state.name(), createdAt, createdAt,
                state == ApplicationState.REJECTED ? "Missing documents" : null,
                state == ApplicationState.REJECTED ? createdAt : null,
                state == ApplicationState.DELETED ? "DUPLICATE" : null,
                state == ApplicationState.DELETED ? createdAt : null, state.name());
    }

    private JsonNode createApplication() throws Exception {
        return createApplication("My application");
    }

    private JsonNode createApplication(String name) throws Exception {
        var response = mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "body", "Initial body"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private UUID applicationInState(ApplicationState state) throws Exception {
        var id = UUID.fromString(createApplication().get("id").asText());
        if (state == ApplicationState.DELETED) {
            performTransition(id, state).andExpect(status().isNoContent());
            return id;
        }
        if (state != ApplicationState.CREATED) {
            performTransition(id, ApplicationState.VERIFIED).andExpect(status().isOk());
        }
        if (state == ApplicationState.ACCEPTED || state == ApplicationState.PUBLISHED) {
            performTransition(id, ApplicationState.ACCEPTED).andExpect(status().isOk());
        }
        if (state == ApplicationState.PUBLISHED || state == ApplicationState.REJECTED) {
            performTransition(id, state).andExpect(status().isOk());
        }
        return id;
    }

    private ResultActions performTransition(UUID id, ApplicationState target) throws Exception {
        return switch (target) {
            case VERIFIED -> mockMvc.perform(put("/applications/{id}/verification", id));
            case ACCEPTED -> mockMvc.perform(put("/applications/{id}/acceptance", id));
            case PUBLISHED -> mockMvc.perform(put("/applications/{id}/publication", id));
            case REJECTED -> mockMvc.perform(put("/applications/{id}/rejection", id)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing documents\"}"));
            case DELETED -> mockMvc.perform(delete("/applications/{id}", id).param("reason", "DUPLICATE"));
            case CREATED -> throw new IllegalArgumentException("Creation is not a transition endpoint");
        };
    }

    private Map<String, Object> storedApplication(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM applications WHERE id = ?", id);
    }

    private Map<String, Object> publicationSequenceState() {
        return jdbcTemplate.queryForMap("SELECT last_value, is_called FROM application_publication_number_seq");
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
