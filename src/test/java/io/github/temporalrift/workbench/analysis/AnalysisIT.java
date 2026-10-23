package io.github.temporalrift.workbench.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.execution.support.FakeGame;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

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

        assertThat(report.path("formatVersion").asInt()).isEqualTo(2);
        assertThat(report.path("complete").asBoolean()).isTrue();
        assertThat(report.path("analysisVersion").asString()).isEqualTo("2");
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
                .contains(",endReason,quantile,failureCode,unit,value,")
                .endsWith(",value,numerator,denominator,status,intervalLower,intervalUpper,unknownCount");
        var metrics = 0;
        for (var cohort : report.path("cohorts")) {
            metrics += cohort.path("metrics").size();
        }
        assertThat(lines).hasSize(1 + metrics);
        assertThat(lines[1])
                .startsWith("2," + runId + "," + report.path("manifestDigest").asString() + ",2,1,true," + BASELINE
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
    void aComparisonStoredUnderAnalysisVersionOneKeepsItsVersionAndItsDocument() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1", "2", "3")));
        var created =
                objectMapper.readTree(read(compare(UUID.randomUUID(), side(runId, BASELINE), side(runId, CANDIDATE))));
        var comparisonId = created.path("comparisonId").asString();
        assertThat(created.path("analysisVersion").asString()).isEqualTo("2");
        jdbc.update("UPDATE analysis_comparison SET analysis_version = '1'");

        var stored = objectMapper.readTree(read(comparison(comparisonId, MediaType.APPLICATION_JSON)));

        assertThat(stored.path("analysisVersion").asString()).isEqualTo("1");
        assertThat(stored.path("cohorts").get(0).path("metricDifferences"))
                .isEqualTo(created.path("cohorts").get(0).path("metricDifferences"));
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
        assertThat(summary.path("analysisVersion").asString()).isEqualTo("2");
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

    @Test
    void aFailedCaseSurfacesItsFinalCodeInTheReportsFailureShare() throws Exception {
        lanes.script(driftingScript());
        var runId = finishedRun(experiment(List.of("1", "2", "3")));

        var report = objectMapper.readTree(read(report(runId, MediaType.APPLICATION_JSON)));

        // The candidate variant pooled cohort follows the eight cohorts of the baseline.
        var pooled = report.path("cohorts").get(8);
        assertThat(pooled.path("variantLabel").asString()).isEqualTo(CANDIDATE);
        var shares = new ArrayList<JsonNode>();
        pooled.path("metrics").forEach(metric -> {
            if ("failure_share".equals(metric.path("name").asString())) {
                shares.add(metric);
            }
        });
        assertThat(shares).hasSize(2);
        assertThat(shares.get(0).path("dimensions").has("failureCode")).isFalse();
        assertThat(shares.get(1).path("dimensions").path("failureCode").asString())
                .isEqualTo("CONFIGURATION_DRIFT");
        assertThat(shares.get(1).path("unit").asString()).isEqualTo("cases");
        assertThat(shares.get(1).path("numerator").asDouble()).isEqualTo(1.0);

        var rows = read(report(runId, CSV)).split("\r\n");
        var columns = Arrays.asList(rows[0].split(",", -1));
        var failureCode = columns.indexOf("failureCode");
        var metricName = columns.indexOf("metric");
        assertThat(failureCode).isEqualTo(columns.indexOf("quantile") + 1);
        var shareCodes = Arrays.stream(rows)
                .skip(1)
                .map(row -> row.split(",", -1))
                .filter(row -> "failure_share".equals(row[metricName])
                        && CANDIDATE.equals(row[columns.indexOf("variantLabel")]))
                .map(row -> row[failureCode])
                .toList();
        assertThat(shareCodes).contains("", "CONFIGURATION_DRIFT");
        Arrays.stream(rows)
                .skip(1)
                .map(row -> row.split(",", -1))
                .filter(row -> !"failure_share".equals(row[metricName]))
                .forEach(row -> assertThat(row[failureCode]).isEmpty());
    }

    @Test
    void aRunVariantIsEvaluatedOnEveryReadAndTheDriftInvalidatesIt() throws Exception {
        lanes.script(driftingScript());
        var runId = finishedRun(experiment(List.of("1", "2", "3")));

        var json = read(evaluation(runId, CANDIDATE, 1, null).andExpect(status().isOk()));
        var evaluation = objectMapper.readTree(json);

        assertThat(evaluation.path("runId").asString()).isEqualTo(runId.toString());
        assertThat(evaluation.path("criterionVersion").asInt()).isEqualTo(1);
        assertThat(evaluation.path("criterionDigest").asString()).matches("[a-f0-9]{64}");
        assertThat(evaluation.path("analysisVersion").asString()).isEqualTo("2");
        assertThat(evaluation.path("analysisSeed").asString()).isEqualTo("1");
        assertThat(evaluation.path("mode").asString()).isEqualTo("SCREENING");
        assertThat(evaluation.path("verdict").asString()).isEqualTo("INVALID");
        assertThat(evaluation.path("score").isNull()).isTrue();
        assertThat(evaluation.path("confirmationSample").isNull()).isTrue();
        assertThat(evaluation.path("validity").path("valid").asBoolean()).isFalse();
        assertThat(evaluation
                        .path("validity")
                        .path("reasons")
                        .get(0)
                        .path("code")
                        .asString())
                .isEqualTo("CONFIGURATION_DRIFT");
        var line = evaluation.path("lines").get(0);
        assertThat(line.path("lineId").asString()).isEqualTo("G-01");
        assertThat(line.path("policyId").asString()).isEqualTo("random-v1");
        assertThat(line.has("interval")).isTrue();
        assertThat(line.has("factionSet")).isTrue();
        assertThat(line.has("weight")).isTrue();
        assertThat(line.path("band").has("lower")).isTrue();
        assertThat(read(evaluation(runId, CANDIDATE, 1, null))).isEqualTo(json);
    }

    @Test
    void anEvaluationOfACleanRunIsUnconfirmedAndConfirmationNeedsTheFullSample() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1", "2", "3")));

        var screening = objectMapper.readTree(
                read(evaluation(runId, BASELINE, 1, "SCREENING").andExpect(status().isOk())));
        var confirmation = objectMapper.readTree(
                read(evaluation(runId, BASELINE, 1, "CONFIRMATION").andExpect(status().isOk())));

        assertThat(screening.path("validity").path("valid").asBoolean()).isTrue();
        assertThat(screening.path("verdict").asString())
                .isNotEqualTo("CONFIRMED")
                .isNotEqualTo("INVALID");
        assertThat(confirmation.path("verdict").asString()).isNotEqualTo("CONFIRMED");
        assertThat(confirmation.path("confirmationSample").path("requiredSeeds").asInt())
                .isEqualTo(100);
        assertThat(confirmation.path("confirmationSample").path("seeds").asInt())
                .isEqualTo(3);
        assertThat(confirmation.path("confirmationSample").path("satisfied").asBoolean())
                .isFalse();
    }

    @Test
    void evaluationRequestsAreValidatedAndScoped() throws Exception {
        lanes.script(this::thresholdDecides);
        var runId = finishedRun(experiment(List.of("1", "2")));

        mockMvc.perform(get("/api/v1/runs/{id}/evaluation", runId)
                        .param("criterionVersion", "1")
                        .with(read()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        evaluation(runId, BASELINE, 1, "NONSENSE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        evaluation(runId, BASELINE, 9999, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        evaluation(runId, "no-such-variant", 1, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        evaluation(UUID.randomUUID(), BASELINE, 1, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/runs/{id}/evaluation", runId)
                        .param("variantLabel", BASELINE)
                        .param("criterionVersion", "1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    void theCriterionCatalogServesVersionOneReadOnly() throws Exception {
        var catalog = objectMapper.readTree(read(
                mockMvc.perform(get("/api/v1/balance-criteria").with(read())).andExpect(status().isOk())));
        var one = objectMapper.readTree(read(
                mockMvc.perform(get("/api/v1/balance-criteria/1").with(read())).andExpect(status().isOk())));

        assertThat(catalog.path("criteria")).hasSize(1);
        assertThat(catalog.path("criteria").get(0)).isEqualTo(one);
        assertThat(one.path("version").asInt()).isEqualTo(1);
        assertThat(one.path("aggregation").asString()).isEqualTo("WEIGHTED_SUM_OF_SQUARED_DEVIATIONS");
        assertThat(one.path("lines")).hasSize(15);
        assertThat(one.path("lines").get(0).path("id").asString()).isEqualTo("G-01");
        assertThat(one.path("lines").get(0).path("weight").isNull()).isTrue();
        assertThat(one.path("evidence").path("confirmationMinimumSeeds").asInt())
                .isEqualTo(100);
        mockMvc.perform(get("/api/v1/balance-criteria/2").with(read()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/balance-criteria").with(read()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }

    private ScriptedLanes.Script driftingScript() {
        return new ScriptedLanes.Script() {
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
        };
    }

    private ResultActions evaluation(UUID runId, String variant, int criterionVersion, String mode) throws Exception {
        var request = get("/api/v1/runs/{id}/evaluation", runId)
                .param("variantLabel", variant)
                .param("criterionVersion", String.valueOf(criterionVersion))
                .with(read());
        return mockMvc.perform(mode == null ? request : request.param("mode", mode));
    }

    private static RequestPostProcessor read() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"));
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
        variants.add(variant(BASELINE, "a"));
        variants.add(variant(CANDIDATE, "f"));
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
