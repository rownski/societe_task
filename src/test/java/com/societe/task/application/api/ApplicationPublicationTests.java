package com.societe.task.application.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.societe.task.application.domain.ApplicationState;
import com.societe.task.application.service.ApplicationService;
import com.societe.task.application.support.ApplicationApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationPublicationTests extends ApplicationApiTestSupport {

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    private Map<String, Object> publicationSequenceState() {
        return jdbcTemplate.queryForMap("SELECT last_value, is_called FROM application_publication_number_seq");
    }
}
