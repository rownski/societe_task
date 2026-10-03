package com.societe.task.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
        assertThat(application.get("rejectionReason").isNull()).isTrue();
        assertThat(application.get("rejectedAt").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(application.get("createdAt").asText())).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM applications WHERE id = ?", String.class, id))
                .isEqualTo("My application");
    }

    @Test
    void editsOnlyBodyAndPreservesName() throws Exception {
        var id = createApplication().get("id").asText();

        mockMvc.perform(patch("/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Updated body\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("My application"))
                .andExpect(jsonPath("$.body").value("Updated body"));

        assertThat(jdbcTemplate.queryForObject("SELECT body FROM applications WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("Updated body");
    }

    @Test
    void rejectsWithReasonAndRepeatedPutIsIdempotent() throws Exception {
        var id = createApplication().get("id").asText();
        var request = "{\"reason\":\"Missing documents\"}";

        var first = mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").value("Missing documents"))
                .andExpect(jsonPath("$.rejectedAt").isString())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(content().json(first));

        assertThat(jdbcTemplate.queryForObject("SELECT rejection_reason FROM applications WHERE id = ?",
                String.class, UUID.fromString(id))).isEqualTo("Missing documents");

        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not eligible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").value("Not eligible"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE", "CREATED_BY_MISTAKE", "NO_LONGER_NEEDED"})
    void softDeletesAndRetainsOriginalReasonOnRepeatedDelete(String reason) throws Exception {
        var id = createApplication().get("id").asText();

        mockMvc.perform(delete("/applications/{id}", id).param("reason", reason))
                .andExpect(status().isNoContent()).andExpect(content().string(""));

        var stored = jdbcTemplate.queryForMap("SELECT * FROM applications WHERE id = ?", UUID.fromString(id));
        assertThat(stored.get("deletion_reason")).isEqualTo(reason);
        assertThat(stored.get("deleted_at")).isNotNull();
        assertThat(stored.get("name")).isEqualTo("My application");
        assertThat(stored.get("body")).isEqualTo("Initial body");

        mockMvc.perform(delete("/applications/{id}", id).param("reason", "NO_LONGER_NEEDED"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForMap("SELECT * FROM applications WHERE id = ?", UUID.fromString(id)))
                .isEqualTo(stored);
    }

    @Test
    void cannotEditOrRejectSoftDeletedApplication() throws Exception {
        var id = createApplication().get("id").asText();
        mockMvc.perform(delete("/applications/{id}", id).param("reason", "DUPLICATE"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Updated body\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing documents\"}"))
                .andExpect(status().isNotFound());

        var stored = jdbcTemplate.queryForMap("SELECT * FROM applications WHERE id = ?", UUID.fromString(id));
        assertThat(stored.get("body")).isEqualTo("Initial body");
        assertThat(stored.get("rejection_reason")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"name\":\"My application\"}", "{\"body\":\"Body\"}",
            "{\"name\":\"  \",\"body\":\"Body\"}", "{\"name\":\"Name\",\"body\":\"  \"}",
            "{\"name\":null,\"body\":\"Body\"}", "{\"name\":\"Name\",\"body\":null}",
            "{\"name\":\"Name\",\"body\":\"Body\",\"unknown\":true}"
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
            "{\"body\":\"Updated body\",\"name\":\"Changed name\"}"})
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
        var id = createApplication().get("id").asText();
        mockMvc.perform(put("/applications/{id}/rejection", id)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject("SELECT rejection_reason FROM applications WHERE id = ?",
                String.class, UUID.fromString(id))).isNull();
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

    private JsonNode createApplication() throws Exception {
        var response = mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My application\",\"body\":\"Initial body\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }
}
