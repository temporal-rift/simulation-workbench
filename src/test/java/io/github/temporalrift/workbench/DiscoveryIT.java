package io.github.temporalrift.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

/** The discovery reads a designer client uses to preview, find and reopen what the workbench holds. */
@WorkbenchIntegrationTest
class DiscoveryIT {

    private static final int PAGE = 500;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private StartRunUseCase startRun;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private ScriptedLanes lanes;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
    }

    @Test
    void aPreviewIsTheMatrixTheFreezeAndTheRunProduceAndStoresNothing() throws Exception {
        var manifest = ExperimentManifests.valid("77", "a".repeat(64), "b".repeat(64));
        var experiments = count("experiment");
        var keys = count("idempotency_key");
        var runs = count("run");

        var preview = objectMapper.readTree(read(preview(manifest, PAGE, 0).andExpect(status().isOk())));

        assertThat(count("experiment")).isEqualTo(experiments);
        assertThat(count("idempotency_key")).isEqualTo(keys);
        assertThat(count("run")).isEqualTo(runs);
        assertThat(preview.path("caseCount").asInt()).isEqualTo(220);
        assertThat(preview.path("cases")).hasSize(220);
        assertThat(sum(preview.path("breakdown"))).isEqualTo(220);
        assertThat(preview.path("breakdown").get(0).path("variantLabel").asString())
                .isEqualTo("baseline");
        var frozen = createExperiment.handle(new CreateExperimentUseCase.Command(UUID.randomUUID(), manifest));
        assertThat(preview.path("manifestDigest").asString()).isEqualTo(frozen.manifestDigest());
        var run = startRun.handle(new StartRunUseCase.Command(frozen.experimentId(), UUID.randomUUID()));
        var runCaseKeys = caseKeys(run.run().runId());
        assertThat(caseKeys(preview.path("cases"))).containsExactlyElementsOf(runCaseKeys);
    }

    @Test
    void aPreviewPagesTheWholeMatrix() throws Exception {
        var manifest = ExperimentManifests.valid("78", "a".repeat(64), "b".repeat(64));

        var last = objectMapper.readTree(read(preview(manifest, 2, 218).andExpect(status().isOk())));
        var beyond = objectMapper.readTree(read(preview(manifest, 2, 400).andExpect(status().isOk())));

        assertThat(last.path("cases")).hasSize(2);
        assertThat(last.path("caseCount").asInt()).isEqualTo(220);
        assertThat(last.path("limit").asInt()).isEqualTo(2);
        assertThat(last.path("offset").asInt()).isEqualTo(218);
        assertThat(beyond.path("cases")).isEmpty();
        assertThat(beyond.path("caseCount").asInt()).isEqualTo(220);
    }

    @Test
    void anInvalidManifestIsRefusedLikeFreezingAndNothingIsStored() throws Exception {
        var experiments = count("experiment");
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var set = manifest.withArray("factionSets").addArray();
        set.add("WEAVERS");
        set.add("WEAVERS");
        set.add("WEAVERS");

        preview(manifest, 10, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPERIMENT"));

        assertThat(count("experiment")).isEqualTo(experiments);
    }

    @Test
    void aFrozenExperimentReopensExactlyAndTheListIsNewestFirst() throws Exception {
        var ids = new ArrayList<UUID>();
        for (var seed : List.of("81", "82", "83")) {
            ids.add(freeze(ExperimentManifests.valid(seed, "a".repeat(64), "b".repeat(64))));
        }
        var newest = ids.getLast();

        mockMvc.perform(get("/api/v1/experiments/{id}", newest).with(read()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experimentId").value(newest.toString()))
                .andExpect(jsonPath("$.manifest.seeds[0]").value("83"))
                .andExpect(jsonPath("$.manifestDigest").isString());
        var page = objectMapper.readTree(read(
                mockMvc.perform(get("/api/v1/experiments").param("limit", "3").with(read()))
                        .andExpect(status().isOk())));

        assertThat(page.path("items")).hasSize(3);
        assertThat(page.path("items").get(0).path("experimentId").asString()).isEqualTo(newest.toString());
        assertThat(page.path("items").get(2).path("experimentId").asString())
                .isEqualTo(ids.getFirst().toString());
        assertThat(page.path("items").get(0).path("name").asString()).isEqualTo("threshold experiment");
        assertThat(page.path("total").asInt()).isEqualTo(count("experiment"));
        mockMvc.perform(get("/api/v1/experiments/{id}", UUID.randomUUID()).with(read()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void runsAreListedNewestFirstAndFilteredByExperimentAndState() throws Exception {
        var experiment = freeze(ExperimentManifests.threePlayerSingleSet());
        var other = freeze(ExperimentManifests.singleSeedSinglePolicySingleVariant("84"));
        var first = start(experiment);
        var second = start(experiment);
        start(other);
        batch.maintain();

        var ofExperiment = objectMapper.readTree(read(mockMvc.perform(get("/api/v1/runs")
                        .param("experimentId", experiment.toString())
                        .with(read()))
                .andExpect(status().isOk())));
        var queued = objectMapper.readTree(read(mockMvc.perform(get("/api/v1/runs")
                        .param("experimentId", experiment.toString())
                        .param("state", "QUEUED")
                        .with(read()))
                .andExpect(status().isOk())));

        assertThat(ofExperiment.path("total").asInt()).isEqualTo(2);
        assertThat(ofExperiment.path("items").get(0).path("runId").asString()).isEqualTo(second.toString());
        assertThat(ofExperiment.path("items").get(1).path("runId").asString()).isEqualTo(first.toString());
        assertThat(ofExperiment
                        .path("items")
                        .get(0)
                        .path("counts")
                        .path("requested")
                        .asInt())
                .isEqualTo(3);
        assertThat(ofExperiment.path("items").get(0).path("failure").isNull()).isTrue();
        assertThat(queued.path("total").asInt()).isLessThanOrEqualTo(2);
        queued.path("items")
                .forEach(run -> assertThat(run.path("state").asString()).isEqualTo("QUEUED"));
    }

    @Test
    void casesAreListedInMatrixOrderAndFilteredByState() throws Exception {
        var run = start(freeze(ExperimentManifests.threePlayerSingleSet()));
        batch.maintain();
        batch.runNextCase("discovery-worker");

        var all = objectMapper.readTree(
                read(mockMvc.perform(get("/api/v1/runs/{id}/cases", run).with(read()))
                        .andExpect(status().isOk())));
        var succeeded = objectMapper.readTree(read(mockMvc.perform(get("/api/v1/runs/{id}/cases", run)
                        .param("state", "SUCCEEDED")
                        .with(read()))
                .andExpect(status().isOk())));
        var paged = objectMapper.readTree(read(mockMvc.perform(get("/api/v1/runs/{id}/cases", run)
                        .param("limit", "1")
                        .param("offset", "2")
                        .with(read()))
                .andExpect(status().isOk())));

        assertThat(all.path("total").asInt()).isEqualTo(3);
        assertThat(caseKeys(all.path("items"))).containsExactlyElementsOf(caseKeys(run));
        assertThat(succeeded.path("total").asInt()).isEqualTo(1);
        assertThat(succeeded.path("items").get(0).path("endReason").asString()).isEqualTo("WIN_CONDITION_MET");
        assertThat(all.path("items").get(2).has("endReason")).isTrue();
        assertThat(all.path("items").get(2).path("endReason").isNull()).isTrue();
        assertThat(paged.path("items")).hasSize(1);
        assertThat(paged.path("items").get(0).path("caseKey").asString())
                .isEqualTo(caseKeys(run).get(2));
        mockMvc.perform(get("/api/v1/runs/{id}/cases", UUID.randomUUID()).with(read()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void theCatalogNamesEveryBundleAManifestMayReference() throws Exception {
        var catalog = objectMapper.readTree(
                read(mockMvc.perform(get("/api/v1/policies").with(read())).andExpect(status().isOk())));

        assertThat(catalog.path("items")).hasSize(2);
        var triples = new ArrayList<String>();
        catalog.path("items").forEach(item -> {
            triples.add(item.path("id").asString() + "|" + item.path("version").asString() + "|"
                    + item.path("artifactDigest").asString());
            assertThat(item.path("description").asString()).isNotBlank();
            assertThat(item.path("parameters").isObject()).isTrue();
        });
        for (var policy : ExperimentManifests.valid().path("policies")) {
            assertThat(triples)
                    .contains(policy.path("id").asString() + "|"
                            + policy.path("version").asString() + "|"
                            + policy.path("artifactDigest").asString());
        }
    }

    @Test
    void discoveryReadsNeedTheirScopes() throws Exception {
        var manifest = ExperimentManifests.valid();
        var write = jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write"));

        for (var path : List.of(
                "/api/v1/experiments",
                "/api/v1/runs",
                "/api/v1/comparisons",
                "/api/v1/policies",
                "/api/v1/experiments/" + UUID.randomUUID(),
                "/api/v1/runs/" + UUID.randomUUID() + "/cases")) {
            mockMvc.perform(get(path).with(write)).andExpect(status().isForbidden());
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/experiment-previews")
                        .with(read())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/experiment-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anEmptyListStillReportsItsTotal() throws Exception {
        mockMvc.perform(get("/api/v1/runs")
                        .param("experimentId", UUID.randomUUID().toString())
                        .with(read()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/v1/comparisons")
                        .param("runId", UUID.randomUUID().toString())
                        .with(read()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void pagingParametersAreBounded() throws Exception {
        mockMvc.perform(get("/api/v1/runs").param("limit", "0").with(read())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/runs").param("limit", "501").with(read())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/runs").param("offset", "-1").with(read())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/runs").with(read()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    private ResultActions preview(JsonNode manifest, int limit, int offset) throws Exception {
        return mockMvc.perform(post("/api/v1/experiment-previews")
                .param("limit", String.valueOf(limit))
                .param("offset", String.valueOf(offset))
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(manifest)));
    }

    private UUID freeze(JsonNode manifest) {
        return createExperiment
                .handle(new CreateExperimentUseCase.Command(UUID.randomUUID(), manifest))
                .experimentId();
    }

    private UUID start(UUID experimentId) {
        return startRun.handle(new StartRunUseCase.Command(experimentId, UUID.randomUUID()))
                .run()
                .runId();
    }

    private List<String> caseKeys(UUID runId) {
        return jdbc.queryForList("SELECT case_key FROM run_case WHERE run_id = ? ORDER BY ordinal", runId).stream()
                .map(row -> row.get("case_key").toString())
                .toList();
    }

    private static List<String> caseKeys(JsonNode cases) {
        var keys = new ArrayList<String>();
        cases.forEach(item -> keys.add(item.path("caseKey").asString()));
        return keys;
    }

    private static int sum(JsonNode breakdown) {
        var total = 0;
        for (var row : breakdown) {
            total += row.path("caseCount").asInt();
        }
        return total;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String read(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor read() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"));
    }
}
