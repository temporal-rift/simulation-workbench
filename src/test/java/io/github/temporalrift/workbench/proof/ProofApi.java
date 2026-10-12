package io.github.temporalrift.workbench.proof;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The designer's published HTTP boundary as a client sees it: every proof step goes through here. */
final class ProofApi {

    static final MediaType CSV = MediaType.parseMediaType("text/csv");

    private static final int PAGE = 500;

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    ProofApi(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    static RequestPostProcessor scopes(String... scopes) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (var scope : scopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        return jwt().authorities(authorities);
    }

    static RequestPostProcessor reader() {
        return scopes("simulation:read");
    }

    static RequestPostProcessor writer() {
        return scopes("simulation:write");
    }

    UUID freeze(JsonNode manifest) throws Exception {
        var created = json(mockMvc.perform(post("/api/v1/experiments")
                        .with(writer())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isCreated()));
        return UUID.fromString(created.path("experimentId").asString());
    }

    JsonNode preview(JsonNode manifest, int limit, int offset) throws Exception {
        return json(mockMvc.perform(post("/api/v1/experiment-previews")
                        .param("limit", String.valueOf(limit))
                        .param("offset", String.valueOf(offset))
                        .with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk()));
    }

    JsonNode experiment(UUID experimentId) throws Exception {
        return json(
                mockMvc.perform(get("/api/v1/experiments/{id}", experimentId).with(reader()))
                        .andExpect(status().isOk()));
    }

    UUID start(UUID experimentId) throws Exception {
        var run = json(mockMvc.perform(post("/api/v1/experiments/{id}/runs", experimentId)
                        .with(writer())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted()));
        return UUID.fromString(run.path("runId").asString());
    }

    ResultActions resume(UUID runId) throws Exception {
        return mockMvc.perform(
                post("/api/v1/runs/{id}/resume", runId).with(writer()).header("Idempotency-Key", UUID.randomUUID()));
    }

    JsonNode run(UUID runId) throws Exception {
        return json(
                mockMvc.perform(get("/api/v1/runs/{id}", runId).with(reader())).andExpect(status().isOk()));
    }

    /** Every case summary of the run, in matrix order, across pages. */
    List<JsonNode> caseSummaries(UUID runId) throws Exception {
        var all = new ArrayList<JsonNode>();
        var total = Integer.MAX_VALUE;
        while (all.size() < total) {
            var page = json(mockMvc.perform(get("/api/v1/runs/{id}/cases", runId)
                            .param("limit", String.valueOf(PAGE))
                            .param("offset", String.valueOf(all.size()))
                            .with(reader()))
                    .andExpect(status().isOk()));
            total = page.path("total").asInt();
            page.path("items").forEach(all::add);
            if (page.path("items").isEmpty()) {
                break;
            }
        }
        return all;
    }

    JsonNode caseOf(UUID runId, String caseId) throws Exception {
        return json(mockMvc.perform(get("/api/v1/runs/{runId}/cases/{caseId}", runId, caseId)
                        .with(reader()))
                .andExpect(status().isOk()));
    }

    ResultActions reportRequest(UUID runId, MediaType accept) throws Exception {
        return mockMvc.perform(
                get("/api/v1/runs/{id}/report", runId).accept(accept).with(reader()));
    }

    JsonNode report(UUID runId) throws Exception {
        return json(reportRequest(runId, MediaType.APPLICATION_JSON).andExpect(status().isOk()));
    }

    String reportText(UUID runId, MediaType accept) throws Exception {
        return text(reportRequest(runId, accept).andExpect(status().isOk()));
    }

    ResultActions compare(JsonNode request) throws Exception {
        return mockMvc.perform(post("/api/v1/comparisons")
                .with(writer())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    ResultActions comparisonRequest(String comparisonId, MediaType accept) throws Exception {
        return mockMvc.perform(
                get("/api/v1/comparisons/{id}", comparisonId).accept(accept).with(reader()));
    }

    ResultActions replay(UUID runId, String caseId, String perspective, Integer seat, RequestPostProcessor token)
            throws Exception {
        var request = get("/api/v1/runs/{runId}/cases/{caseId}/replay", runId, caseId)
                .param("perspective", perspective)
                .with(token);
        if (seat != null) {
            request = request.param("seatIndex", seat.toString());
        }
        return mockMvc.perform(request);
    }

    ResultActions reproduce(UUID runId, String caseId, RequestPostProcessor token, UUID key) throws Exception {
        return mockMvc.perform(post("/api/v1/runs/{runId}/cases/{caseId}/reproductions", runId, caseId)
                .with(token)
                .header("Idempotency-Key", key));
    }

    JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(text(actions));
    }

    static String text(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
