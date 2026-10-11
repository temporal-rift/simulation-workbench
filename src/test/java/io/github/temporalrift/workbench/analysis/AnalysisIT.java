package io.github.temporalrift.workbench.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.execution.support.FakeGame;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

@WorkbenchIntegrationTest
class AnalysisIT {

    private static final String WORKER = "analysis-worker";
    private static final String BASELINE = "threshold-20";
    private static final String CANDIDATE = "threshold-22";
    private static final MediaType CSV = MediaType.parseMediaType("text/csv");

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
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM analysis_comparison");
        jdbc.update("DELETE FROM analysis_case_fact");
        jdbc.update("DELETE FROM evidence_event");
        jdbc.update("DELETE FROM evidence_step");
        jdbc.update("DELETE FROM evidence_source_offset");
        jdbc.update("DELETE FROM case_evidence");
        jdbc.update("DELETE FROM evidence_artifact");
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
    }

    @Test
    void aRunReportIsStratifiedAttributedAndRegeneratesIdentically() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1", "2", "3")));

        var json = read(report(runId, MediaType.APPLICATION_JSON).andExpect(status().isOk()));
        var report = objectMapper.readTree(json);

        assertThat(report.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(report.path("complete").asBoolean()).isTrue();
        assertThat(report.path("analysisVersion").asString()).isEqualTo("1");
        assertThat(report.path("caseCounts").path("succeeded").asInt()).isEqualTo(18);
        assertThat(report.path("cohorts")).hasSize(2 * (1 + 3 + 1 + 3));
        var pooled = report.path("cohorts").get(0);
        assertThat(pooled.path("variantLabel").asString()).isEqualTo(BASELINE);
        assertThat(pooled.has("factionSet")).isTrue();
        assertThat(pooled.path("factionSet").isNull()).isTrue();
        assertThat(pooled.path("seatIndex").isNull()).isTrue();
        assertThat(pooled.path("eligibleGames").asInt()).isEqualTo(9);
        assertThat(pooled.path("independentBlocks").asInt()).isEqualTo(3);
        var erasers = metric(pooled, "faction_win_rate", "ERASERS");
        assertThat(erasers.path("numerator").asDouble()).isEqualTo(3.0);
        assertThat(erasers.path("denominator").asInt()).isEqualTo(9);
        assertThat(erasers.path("statistic").asString()).isEqualTo("RATE");
        assertThat(erasers.path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(erasers.path("interval").path("method").asString()).isEqualTo("BLOCK_BOOTSTRAP");
        assertThat(read(report(runId, MediaType.APPLICATION_JSON))).isEqualTo(json);
    }

    @Test
    void aRunReportExportsAsCsvWithItsAttributionOnEveryRow() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1", "2")));

        var csv = read(report(runId, CSV).andExpect(status().isOk()));
        var report = objectMapper.readTree(read(report(runId, MediaType.APPLICATION_JSON)));

        var lines = csv.split("\r\n");
        assertThat(lines[0])
                .startsWith("formatVersion,runId,manifestDigest,analysisVersion,analysisSeed,complete,")
                .endsWith(",value,numerator,denominator,status,intervalLower,intervalUpper,unknownCount");
        var metrics = 0;
        for (var cohort : report.path("cohorts")) {
            metrics += cohort.path("metrics").size();
        }
        assertThat(lines).hasSize(1 + metrics);
        assertThat(lines[1])
                .startsWith("1," + runId + "," + report.path("manifestDigest").asString() + ",1,1,true," + BASELINE
                        + ",random,");
        assertThat(csv).doesNotContainIgnoringCase("bearer").doesNotContainIgnoringCase("token");
        assertThat(read(report(runId, CSV))).isEqualTo(csv);
    }

    @Test
    void anUnknownRunHasNoReportAndReadingNeedsTheReadScope() throws Exception {
        report(UUID.randomUUID(), MediaType.APPLICATION_JSON)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/runs/{id}/report", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void twoVariantsOfOneRunCompareFromMatchedSeedsAndRegenerateIdentically() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1", "2", "3")));
        var key = UUID.randomUUID();

        var created = objectMapper.readTree(
                read(compare(key, side(runId, BASELINE), side(runId, CANDIDATE)).andExpect(status().isCreated())));

        assertThat(created.path("complete").asBoolean()).isTrue();
        assertThat(created.path("declaredDifferences").get(0).path("field").asString())
                .isEqualTo("RULES");
        assertThat(created.path("matchedBlocks").asInt()).isEqualTo(3);
        assertThat(created.path("excludedPairs")).isEmpty();
        var pooled = created.path("cohorts").get(0);
        var erasers = difference(pooled, "faction_win_rate", "ERASERS");
        assertThat(erasers.path("baselineValue").asDouble()).isEqualTo(0.333333);
        assertThat(erasers.path("candidateValue").asDouble()).isEqualTo(1.0);
        assertThat(erasers.path("difference").asDouble()).isEqualTo(0.666667);
        var comparisonId = created.path("comparisonId").asString();
        var json = read(comparison(comparisonId, MediaType.APPLICATION_JSON).andExpect(status().isOk()));
        assertThat(read(comparison(comparisonId, MediaType.APPLICATION_JSON))).isEqualTo(json);
        var csv = read(comparison(comparisonId, CSV).andExpect(status().isOk()));
        assertThat(csv.split("\r\n")[0])
                .startsWith("formatVersion,comparisonId,baselineRunId,baselineVariantLabel,candidateRunId,")
                .endsWith(",baselineValue,candidateValue,difference,status,intervalLower,intervalUpper");
        assertThat(read(comparison(comparisonId, CSV))).isEqualTo(csv);
    }

    @Test
    void storedComparisonsAreListedNewestFirstAsSummariesAndFilteredByRun() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1", "2")));
        var other = startedRun(experiment(List.of("1", "2")));
        var first = objectMapper
                .readTree(read(compare(UUID.randomUUID(), side(runId, BASELINE), side(runId, CANDIDATE))))
                .path("comparisonId")
                .asString();
        var second = objectMapper
                .readTree(read(compare(UUID.randomUUID(), side(runId, CANDIDATE), side(runId, BASELINE))))
                .path("comparisonId")
                .asString();

        var listed = objectMapper.readTree(read(mockMvc.perform(get("/api/v1/comparisons")
                        .param("runId", runId.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isOk())));
        var unrelated = objectMapper.readTree(read(mockMvc.perform(get("/api/v1/comparisons")
                        .param("runId", other.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isOk())));

        assertThat(listed.path("total").asInt()).isEqualTo(2);
        assertThat(listed.path("items").get(0).path("comparisonId").asString()).isEqualTo(second);
        assertThat(listed.path("items").get(1).path("comparisonId").asString()).isEqualTo(first);
        var summary = listed.path("items").get(1);
        assertThat(summary.path("baseline").path("variantLabel").asString()).isEqualTo(BASELINE);
        assertThat(summary.path("candidate").path("runId").asString()).isEqualTo(runId.toString());
        assertThat(summary.path("analysisVersion").asString()).isEqualTo("1");
        assertThat(summary.path("createdAt").asString()).isNotBlank();
        assertThat(summary.has("cohorts")).isFalse();
        assertThat(unrelated.path("total").asInt()).isZero();
    }

    @Test
    void aComparisonIsIdempotentByKey() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1")));
        var key = UUID.randomUUID();

        var first = objectMapper.readTree(read(compare(key, side(runId, BASELINE), side(runId, CANDIDATE))));
        var repeat = objectMapper.readTree(
                read(compare(key, side(runId, BASELINE), side(runId, CANDIDATE)).andExpect(status().isCreated())));

        assertThat(repeat.path("comparisonId")).isEqualTo(first.path("comparisonId"));
        compare(key, side(runId, CANDIDATE), side(runId, BASELINE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidAndIncomparableRequestsAreRefusedWithTheirCodes() throws Exception {
        var runId = startedRun(experiment(List.of("1", "2")));
        var otherSeeds = startedRun(experiment(List.of("1", "2", "3")));

        compare(UUID.randomUUID(), side(runId, BASELINE), side(runId, "threshold-99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPARISON"));
        compare(UUID.randomUUID(), side(runId, BASELINE), side(runId, BASELINE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPARISON"));
        mockMvc.perform(post("/api/v1/comparisons")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baselineRunId\":\"" + runId + "\",\"candidateRunId\":\"" + runId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPARISON"));
        compare(UUID.randomUUID(), side(runId, BASELINE), side(otherSeeds, CANDIDATE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INCOMPARABLE_RUNS"))
                .andExpect(jsonPath("$.detail").value("The runs differ in seeds"));
        compare(UUID.randomUUID(), side(UUID.randomUUID(), BASELINE), side(runId, CANDIDATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_comparison", Integer.class))
                .isZero();
    }

    @Test
    void aFailedCounterpartIsListedAndKeepsTheComparisonPartial() throws Exception {
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return thresholdDecides(context);
            }

            @Override
            public void onPoll(CaseLane.CaseContext context, FakeGame game) {
                if (CANDIDATE.equals(variantOf(context))
                        && "2".equals(context.seed())
                        && "ERASERS".equals(context.seats().getFirst().faction())) {
                    throw new AttemptFailedException(FailureCode.CONFIGURATION_DRIFT, "the lane drifted");
                }
            }
        });
        var runId = finishedRun(experiment(List.of("1", "2", "3")));

        var comparison =
                objectMapper.readTree(read(compare(UUID.randomUUID(), side(runId, BASELINE), side(runId, CANDIDATE))
                        .andExpect(status().isCreated())));

        assertThat(comparison.path("complete").asBoolean()).isFalse();
        assertThat(comparison.path("failedCounterparts").asInt()).isEqualTo(1);
        var excluded = comparison.path("excludedPairs").get(0);
        assertThat(excluded.path("reason").asString()).isEqualTo("FAILED_COUNTERPART");
        assertThat(excluded.path("seed").asString()).isEqualTo("2");
        assertThat(excluded.path("candidateState").asString()).isEqualTo("FAILED");
        assertThat(comparison.path("cohorts").get(0).path("matchedBlocks").asInt())
                .isEqualTo(2);
        assertThat(comparison.path("cohorts").get(0).path("excludedBlocks").asInt())
                .isEqualTo(1);
    }

    /** Seat 0 wins under the baseline threshold; Erasers win wherever they sit under the candidate threshold. */
    private GameSession.AuthoritativeEnding thresholdDecides(CaseLane.CaseContext context) {
        var scores = context.seats().stream()
                .map(seat -> new FinalScore(seat.seatIndex(), seat.faction(), 30 - seat.seatIndex() * 4))
                .toList();
        var winner = CANDIDATE.equals(variantOf(context))
                ? context.seats().stream()
                        .filter(seat -> "ERASERS".equals(seat.faction()))
                        .findFirst()
                        .orElseThrow()
                : context.seats().getFirst();
        return new GameSession.AuthoritativeEnding(
                EndReason.WIN_CONDITION_MET,
                List.of(new Winner(winner.seatIndex(), winner.faction(), WinType.SCORE_THRESHOLD)),
                scores,
                3);
    }

    private String variantOf(CaseLane.CaseContext context) {
        return jdbc.queryForObject(
                "SELECT variant_label FROM run_case WHERE case_id = ?", String.class, context.caseId());
    }

    private JsonNode experiment(List<String> seeds) {
        var manifest = (ObjectNode) ExperimentManifests.threePlayerSingleSet().deepCopy();
        var seedValues = objectMapper.createArrayNode();
        seeds.forEach(seedValues::add);
        manifest.set("seeds", seedValues);
        var variants = objectMapper.createArrayNode();
        variants.add(variant(BASELINE, "1"));
        variants.add(variant(CANDIDATE, "2"));
        manifest.set("variants", variants);
        return manifest;
    }

    private ObjectNode variant(String label, String rules) {
        var template = (ObjectNode) ExperimentManifests.threePlayerSingleSet()
                .path("variants")
                .get(0)
                .deepCopy();
        template.put("label", label);
        template.put("effectiveRulesDigest", rules.repeat(64));
        return template;
    }

    private UUID startedRun(JsonNode manifest) {
        var experimentId = createExperiment
                .handle(new CreateExperimentUseCase.Command(UUID.randomUUID(), manifest))
                .experimentId();
        return startRun.handle(new StartRunUseCase.Command(experimentId, UUID.randomUUID()))
                .run()
                .runId();
    }

    private UUID finishedRun(JsonNode manifest) {
        var runId = startedRun(manifest);
        batch.maintain();
        while (batch.runNextCase(WORKER)) {
            // run every case
        }
        batch.maintain();
        return runId;
    }

    private ResultActions report(UUID runId, MediaType accept) throws Exception {
        return mockMvc.perform(get("/api/v1/runs/{id}/report", runId)
                .accept(accept)
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))));
    }

    private ResultActions comparison(String comparisonId, MediaType accept) throws Exception {
        return mockMvc.perform(get("/api/v1/comparisons/{id}", comparisonId)
                .accept(accept)
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))));
    }

    private ResultActions compare(UUID key, ObjectNode baseline, ObjectNode candidate) throws Exception {
        var body = objectMapper.createObjectNode();
        body.set("baseline", baseline);
        body.set("candidate", candidate);
        return mockMvc.perform(post("/api/v1/comparisons")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ObjectNode side(UUID runId, String variant) {
        var side = objectMapper.createObjectNode();
        side.put("runId", runId.toString());
        side.put("variantLabel", variant);
        return side;
    }

    private static String read(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static JsonNode metric(JsonNode cohort, String name, String faction) {
        for (var metric : cohort.path("metrics")) {
            if (name.equals(metric.path("name").asString())
                    && faction.equals(metric.path("dimensions").path("faction").asString())
                    && !metric.path("dimensions").has("quantile")) {
                return metric;
            }
        }
        throw new AssertionError("no " + name + " for " + faction);
    }

    private static JsonNode difference(JsonNode cohort, String name, String faction) {
        for (var difference : cohort.path("metricDifferences")) {
            if (name.equals(difference.path("name").asString())
                    && faction.equals(
                            difference.path("dimensions").path("faction").asString())
                    && !difference.path("dimensions").has("quantile")) {
                return difference;
            }
        }
        throw new AssertionError("no " + name + " for " + faction);
    }
}
