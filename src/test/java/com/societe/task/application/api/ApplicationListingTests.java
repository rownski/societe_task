package com.societe.task.application.api;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.societe.task.application.domain.ApplicationState;
import com.societe.task.application.support.ApplicationApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationListingTests extends ApplicationApiTestSupport {

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
        insertListingFixture(lowId, "Listing fixture", ApplicationState.CREATED, earlier);
        insertListingFixture(highId, "Listing fixture", ApplicationState.CREATED, earlier);
        insertListingFixture(newestId, "Listing fixture", ApplicationState.CREATED, earlier.plusDays(1));

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
}
