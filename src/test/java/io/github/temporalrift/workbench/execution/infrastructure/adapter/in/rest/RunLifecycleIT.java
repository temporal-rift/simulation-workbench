package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

@WorkbenchIntegrationTest
class RunLifecycleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private ScriptedLanes lanes;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanRuns() {
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
    }

    @Test
    void startRunAcceptsAQueuedRunWithEveryCasePending() throws Exception {
        var experimentId = experiment(ExperimentManifests.threePlayerSingleSet());

        mockMvc.perform(startRun(experimentId, UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isString())
                .andExpect(jsonPath("$.experimentId").value(experimentId.toString()))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.counts.requested").value(3))
                .andExpect(jsonPath("$.counts.pending").value(3))
                .andExpect(jsonPath("$.counts.running").value(0))
                .andExpect(jsonPath("$.counts.succeeded").value(0))
                .andExpect(jsonPath("$.counts.failed").value(0))
                .andExpect(jsonPath("$.counts.cancelled").value(0))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.failure").value(nullValue()));
    }

    @Test
    void repeatedStartWithTheSameKeyReturnsTheOriginalRunAndCreatesNoSecondRun() throws Exception {
        var experimentId = experiment(ExperimentManifests.threePlayerSingleSet());
        var key = UUID.randomUUID();

        var first = runId(mockMvc.perform(startRun(experimentId, key))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var second = runId(mockMvc.perform(startRun(experimentId, key))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_case", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void reusingAKeyForAnotherExperimentConflicts() throws Exception {
        var key = UUID.randomUUID();
        mockMvc.perform(startRun(experiment(ExperimentManifests.threePlayerSingleSet()), key))
                .andExpect(status().isAccepted());

        mockMvc.perform(startRun(experiment(ExperimentManifests.singleSeedSinglePolicySingleVariant("7")), key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void unknownExperimentAndRunAreNotFound() throws Exception {
        mockMvc.perform(startRun(UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/runs/{id}", UUID.randomUUID()).with(jwt().authorities(readAuthority())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/runs/{id}/cancel", UUID.randomUUID())
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRunReportsDurableProgress() throws Exception {
        var runId = start(experiment(ExperimentManifests.threePlayerSingleSet()));
        batch.maintain();
        batch.runNextCase("rest-worker");

        mockMvc.perform(get("/api/v1/runs/{id}", runId).with(jwt().authorities(readAuthority())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.counts.requested").value(3))
                .andExpect(jsonPath("$.counts.succeeded").value(1))
                .andExpect(jsonPath("$.counts.pending").value(2));
    }

    @Test
    void resumeOnACompletedRunIsAnInvalidRunState() throws Exception {
        var runId = start(experiment(ExperimentManifests.threePlayerSingleSet()));
        batch.maintain();
        while (batch.runNextCase("rest-worker")) {
            // run every case
        }
        batch.maintain();

        mockMvc.perform(post("/api/v1/runs/{id}/resume", runId)
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RUN_STATE"));
    }

    @Test
    void cancelAcceptsOnceThenReportsTheTerminalRun() throws Exception {
        var runId = start(experiment(ExperimentManifests.threePlayerSingleSet()));

        mockMvc.perform(post("/api/v1/runs/{id}/cancel", runId)
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.counts.cancelled").value(3))
                .andExpect(jsonPath("$.counts.succeeded").value(0));

        mockMvc.perform(post("/api/v1/runs/{id}/cancel", runId)
                        .with(jwt().authorities(writeAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void getCaseExposesTheCaseTheAttemptsAndTheResult() throws Exception {
        var runId = start(experiment(ExperimentManifests.threePlayerSingleSet()));
        batch.maintain();
        while (batch.runNextCase("rest-worker")) {
            // run every case
        }
        var caseId = jdbc.queryForObject(
                "SELECT case_id FROM run_case WHERE run_id = ? ORDER BY ordinal LIMIT 1", UUID.class, runId);

        mockMvc.perform(get("/api/v1/runs/{runId}/cases/{caseId}", runId, caseId)
                        .with(jwt().authorities(readAuthority())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.playerCount").value(3))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.assignments.length()").value(3))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.endReason").value("WIN_CONDITION_MET"))
                .andExpect(jsonPath("$.result.finalScores.length()").value(3));
    }

    @Test
    void runOperationsRequireTheirScopes() throws Exception {
        var experimentId = experiment(ExperimentManifests.threePlayerSingleSet());

        mockMvc.perform(post("/api/v1/experiments/{id}/runs", experimentId)
                        .with(jwt().authorities(readAuthority()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/runs/{id}", UUID.randomUUID()).with(jwt().authorities(writeAuthority())))
                .andExpect(status().isForbidden());
    }

    @Test
    void operationsOwnedByLaterPackagesStayExplicitlyUnavailable() throws Exception {
        var runId = start(experiment(ExperimentManifests.threePlayerSingleSet()));

        mockMvc.perform(get("/api/v1/runs/{id}/report", runId).with(jwt().authorities(readAuthority())))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
    }

    @Test
    void aMissingIdempotencyKeyIsABadRequestOnEveryMutation() throws Exception {
        var experimentId = experiment(ExperimentManifests.threePlayerSingleSet());
        var runId = start(experimentId);

        mockMvc.perform(post("/api/v1/experiments/{id}/runs", experimentId)
                        .with(jwt().authorities(writeAuthority()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/runs/{id}/cancel", runId).with(jwt().authorities(writeAuthority())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/runs/{id}/resume", runId).with(jwt().authorities(writeAuthority())))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder startRun(UUID experimentId, UUID key) {
        return post("/api/v1/experiments/{id}/runs", experimentId)
                .with(jwt().authorities(writeAuthority()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private UUID start(UUID experimentId) throws Exception {
        return runId(mockMvc.perform(startRun(experimentId, UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private UUID runId(String body) throws Exception {
        return UUID.fromString(objectMapper.readTree(body).get("runId").asString());
    }

    private UUID experiment(JsonNode manifest) {
        return createExperiment
                .handle(new CreateExperimentUseCase.Command(UUID.randomUUID(), manifest))
                .experimentId();
    }

    private static SimpleGrantedAuthority writeAuthority() {
        return new SimpleGrantedAuthority("SCOPE_simulation:write");
    }

    private static SimpleGrantedAuthority readAuthority() {
        return new SimpleGrantedAuthority("SCOPE_simulation:read");
    }
}
