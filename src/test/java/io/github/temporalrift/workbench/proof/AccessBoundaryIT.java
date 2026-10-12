package io.github.temporalrift.workbench.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Participant and operator credentials are not designer credentials: every operation refuses them. */
class AccessBoundaryIT extends ProofSupport {

    @Test
    void controlOnlyAndParticipantOnlyTokensAreRefusedOnEveryOperationAndLearnNothing() throws Exception {
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        drain();
        var caseId = api.caseSummaries(runId).getFirst().path("caseId").asString();
        var manifest = objectMapper.writeValueAsString(threePlayerManifest(List.of("42"), false));
        var secrets = List.of(runId.toString(), caseId, experimentId.toString());

        for (var token : tokens()) {
            for (var request : requests(runId, caseId, manifest)) {
                var body = mockMvc.perform(request.with(token))
                        .andExpect(status().isForbidden())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
                assertThat(body).as(request.toString()).doesNotContain(secrets.toArray(String[]::new));
            }
        }
    }

    @Test
    void unauthenticatedCallersAreRefusedBeforeAnyOperationRuns() throws Exception {
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        drain();
        var caseId = api.caseSummaries(runId).getFirst().path("caseId").asString();

        for (var request : requests(runId, caseId, "{}")) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    /** A bot's participant authority, the operator's control authority, and a token with no scope at all. */
    private static List<RequestPostProcessor> tokens() {
        return List.of(
                ProofApi.scopes("simulation:control"),
                ProofApi.scopes("participant"),
                ProofApi.scopes("participant", "simulation:control"),
                jwt());
    }

    private static List<MockHttpServletRequestBuilder> requests(UUID runId, String caseId, String manifest) {
        var key = UUID.randomUUID();
        return List.of(
                get("/api/v1/experiments"),
                get("/api/v1/experiments/{id}", UUID.randomUUID()),
                get("/api/v1/runs"),
                get("/api/v1/runs/{id}", runId),
                get("/api/v1/runs/{id}/cases", runId),
                get("/api/v1/runs/{id}/cases/{caseId}", runId, caseId),
                get("/api/v1/runs/{id}/report", runId).accept(MediaType.APPLICATION_JSON),
                get("/api/v1/runs/{id}/report", runId).accept(ProofApi.CSV),
                get("/api/v1/comparisons"),
                get("/api/v1/policies"),
                get("/api/v1/runs/{id}/cases/{caseId}/replay", runId, caseId).param("perspective", "OBSERVER"),
                get("/api/v1/runs/{id}/cases/{caseId}/replay", runId, caseId)
                        .param("perspective", "PLAYER")
                        .param("seatIndex", "0"),
                post("/api/v1/experiments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manifest),
                post("/api/v1/experiment-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manifest),
                post("/api/v1/runs/{id}/cancel", runId).header("Idempotency-Key", key),
                post("/api/v1/runs/{id}/resume", runId).header("Idempotency-Key", key),
                post("/api/v1/runs/{id}/cases/{caseId}/reproductions", runId, caseId)
                        .header("Idempotency-Key", key));
    }
}
