package com.societe.task.application.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.societe.task.application.domain.ApplicationState;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
public abstract class ApplicationApiTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    protected void cleanApplications() {
        jdbcTemplate.update("DELETE FROM application_state_history");
        jdbcTemplate.update("DELETE FROM applications");
    }

    protected JsonNode createApplication() throws Exception {
        var response = mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "My application", "body", "Initial body"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    protected UUID applicationInState(ApplicationState state) throws Exception {
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

    protected ResultActions performTransition(UUID id, ApplicationState target) throws Exception {
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

    protected Map<String, Object> storedApplication(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM applications WHERE id = ?", id);
    }

    protected List<Map<String, Object>> stateHistory(UUID id) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM application_state_history WHERE application_id = ? ORDER BY id", id);
    }
}
