package io.github.temporalrift.workbench.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

/** Reports of multi-seed runs: sample sizes, uncertainty and pairing stated as they are, in JSON and in CSV. */
class MultiSeedReportingIT extends ProofSupport {

    private static final List<String> TEN_SEEDS = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    private static final List<String> ERASERS_SET = List.of("ERASERS", "PROPHETS", "WEAVERS");

    @Test
    void tenErasersSamplesWithFourWinsOneOfThemSharedReportAndExportTheSameFigures() throws Exception {
        lanes.script(erasersWinSeedsOneToFour());
        var runId = startRun(threePlayerManifest(TEN_SEEDS, false));
        drain();

        var json = api.reportText(runId, org.springframework.http.MediaType.APPLICATION_JSON);
        var report = objectMapper.readTree(json);
        var csv = api.reportText(runId, ProofApi.CSV);

        assertThat(report.path("complete").asBoolean()).isTrue();
        var seatZero = cohort(report, ERASERS_SET, 0);
        assertThat(seatZero.path("independentBlocks").asInt()).isEqualTo(10);
        var wins = metric(seatZero, "faction_win_rate", ERASERS);
        assertThat(wins.path("numerator").asDouble()).isEqualTo(4.0);
        assertThat(wins.path("denominator").asInt()).isEqualTo(10);
        assertThat(wins.path("value").asDouble()).isEqualTo(0.4);
        assertThat(wins.path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(wins.path("interval").path("method").asString()).isEqualTo("BLOCK_BOOTSTRAP");
        assertThat(wins.path("interval").path("level").asDouble()).isEqualTo(0.95);
        var shared = metric(seatZero, "faction_shared_win_rate", ERASERS);
        assertThat(shared.path("numerator").asDouble()).isEqualTo(1.0);
        assertThat(shared.path("denominator").asInt()).isEqualTo(10);
        assertThat(shared.path("value").asDouble()).isEqualTo(0.1);

        var winsRow = csvRow(csv, "faction_win_rate", ERASERS, "0");
        assertThat(Double.parseDouble(winsRow.get("numerator"))).isEqualTo(4.0);
        assertThat(winsRow).containsEntry("denominator", "10");
        assertThat(Double.parseDouble(winsRow.get("value"))).isEqualTo(0.4);
        var sharedRow = csvRow(csv, "faction_shared_win_rate", ERASERS, "0");
        assertThat(Double.parseDouble(sharedRow.get("numerator"))).isEqualTo(1.0);
        assertThat(Double.parseDouble(sharedRow.get("value"))).isEqualTo(0.1);

        assertThat(api.reportText(runId, org.springframework.http.MediaType.APPLICATION_JSON))
                .isEqualTo(json);
        assertThat(api.reportText(runId, ProofApi.CSV)).isEqualTo(csv);

        var cases = new ArrayList<JsonNode>();
        for (var summary : api.caseSummaries(runId)) {
            cases.add(api.caseOf(runId, summary.path("caseId").asString()));
        }
        var writer = new EvidenceWriter(objectMapper, EvidenceWriter.DEFAULT_DIRECTORY);
        var document = writer.multiSeedSample(api.experiment(experimentId), api.run(runId), report, cases);
        var file = writer.write("multi-seed-sample.json", document);
        assertThat(document.path("cases")).hasSize(30);
        assertThat(document.path("cohorts")).isEqualTo(report.path("cohorts"));
        assertThat(Files.readString(file)).contains("\"manifestDigest\"", "\"analysisVersion\"", "\"timingMode\"");
    }

    @Test
    void oneIndependentBlockStatesInsufficientSampleAndClaimsNoInterval() throws Exception {
        lanes.script(erasersWinSeedsOneToFour());
        var runId = startRun(threePlayerManifest(List.of("1"), false));
        drain();

        var report = api.report(runId);

        var seatZero = cohort(report, ERASERS_SET, 0);
        assertThat(seatZero.path("independentBlocks").asInt()).isEqualTo(1);
        var wins = metric(seatZero, "faction_win_rate", ERASERS);
        assertThat(wins.path("status").asString()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(wins.path("interval").isNull()).isTrue();
        var csv = api.reportText(runId, ProofApi.CSV);
        var row = csvRow(csv, "faction_win_rate", ERASERS, "0");
        assertThat(row).containsEntry("status", "INSUFFICIENT_SAMPLE");
        assertThat(row.get("intervalLower")).isEmpty();
        assertThat(row.get("intervalUpper")).isEmpty();
    }

    @Test
    void aFailedCounterpartIsListedWithItsFailureAndLeftOutOfThePairedEstimate() throws Exception {
        lanes.script(new ScriptedLanes.Script() {
            /** Baseline Erasers win only seeds 1 and 2; under the candidate rules they always win. */
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                var wins = CANDIDATE.equals(variantOf(context))
                        || List.of("1", "2").contains(context.seed());
                return erasersOrOthersWin(context, wins, false);
            }

            /** The candidate's Erasers-first game of seed 3 never completes. */
            @Override
            public void onOpen(CaseLane.CaseContext context) {
                if (CANDIDATE.equals(variantOf(context))
                        && "3".equals(context.seed())
                        && ERASERS.equals(context.seats().getFirst().faction().name())) {
                    throw new AttemptFailedException(FailureCode.CONFIGURATION_DRIFT, "the lane drifted");
                }
            }
        });
        var runId = startRun(threePlayerManifest(List.of("1", "2", "3", "4"), true));
        drain();
        assertThat(api.run(runId).path("counts").path("failed").asInt()).isEqualTo(1);

        var request = objectMapper.createObjectNode();
        request.set("baseline", side(runId, BASELINE));
        request.set("candidate", side(runId, CANDIDATE));
        var comparison = api.json(api.compare(request).andExpect(status().isCreated()));

        assertThat(comparison.path("complete").asBoolean()).isFalse();
        assertThat(comparison.path("failedCounterparts").asInt()).isEqualTo(1);
        var excluded = comparison.path("excludedPairs");
        assertThat(excluded).hasSize(1);
        assertThat(excluded.get(0).path("reason").asString()).isEqualTo("FAILED_COUNTERPART");
        assertThat(excluded.get(0).path("seed").asString()).isEqualTo("3");
        assertThat(excluded.get(0).path("candidateState").asString()).isEqualTo("FAILED");
        var failedCase =
                api.caseOf(runId, excluded.get(0).path("candidateCaseId").asString());
        var attempts = failedCase.path("attempts");
        assertThat(attempts.get(attempts.size() - 1)
                        .path("failure")
                        .path("code")
                        .asString())
                .isEqualTo("CONFIGURATION_DRIFT");
        var pooled = pooled(comparison);
        assertThat(pooled.path("matchedBlocks").asInt()).isEqualTo(3);
        assertThat(pooled.path("excludedBlocks").asInt()).isEqualTo(1);
        var erasers = difference(pooled, "faction_win_rate", ERASERS);
        // Seeds 1, 2 and 4 are matched: the baseline wins 2 of those 3 blocks, not 2 of all 4.
        assertThat(erasers.path("baselineValue").asDouble()).isEqualTo(0.666667);
        assertThat(erasers.path("candidateValue").asDouble()).isEqualTo(1.0);
        assertThat(erasers.path("difference").asDouble()).isEqualTo(0.333333);
        var id = comparison.path("comparisonId").asString();
        var json = ProofApi.text(api.comparisonRequest(id, org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(ProofApi.text(api.comparisonRequest(id, org.springframework.http.MediaType.APPLICATION_JSON)))
                .isEqualTo(json);
        assertThat(ProofApi.text(api.comparisonRequest(id, ProofApi.CSV))).startsWith("formatVersion,comparisonId");
    }

    /** Erasers win in seeds 1 to 4, in seed 4 jointly with another seat; in every other seed another seat wins. */
    private ScriptedLanes.Script erasersWinSeedsOneToFour() {
        return context -> {
            var seed = Integer.parseInt(context.seed());
            return erasersOrOthersWin(context, seed <= 4, seed == 4);
        };
    }

    private static GameSession.AuthoritativeEnding erasersOrOthersWin(
            CaseLane.CaseContext context, boolean erasersWin, boolean alsoAnotherSeat) {
        var scores = context.seats().stream()
                .map(seat -> new FinalScore(seat.seatIndex(), seat.faction(), 30 - seat.seatIndex() * 4))
                .toList();
        var erasers = context.seats().stream()
                .filter(seat -> ERASERS.equals(seat.faction().name()))
                .findFirst()
                .orElseThrow();
        var other = context.seats().stream()
                .filter(seat -> !ERASERS.equals(seat.faction().name()))
                .findFirst()
                .orElseThrow();
        var winners = new ArrayList<Winner>();
        if (erasersWin) {
            winners.add(new Winner(erasers.seatIndex(), erasers.faction(), WinType.SCORE_THRESHOLD));
        }
        if (!erasersWin || alsoAnotherSeat) {
            winners.add(new Winner(other.seatIndex(), other.faction(), WinType.SCORE_THRESHOLD));
        }
        return new GameSession.AuthoritativeEnding(EndReason.WIN_CONDITION_MET, winners, scores, 3);
    }

    private String variantOf(CaseLane.CaseContext context) {
        return jdbc.queryForObject(
                "SELECT variant_label FROM run_case WHERE case_id = ?", String.class, context.caseId());
    }

    private tools.jackson.databind.node.ObjectNode side(UUID runId, String variant) {
        var side = objectMapper.createObjectNode();
        side.put("runId", runId.toString());
        side.put("variantLabel", variant);
        return side;
    }

    private static JsonNode cohort(JsonNode report, List<String> factionSet, int seatIndex) {
        for (var cohort : report.path("cohorts")) {
            var set = new ArrayList<String>();
            cohort.path("factionSet").forEach(faction -> set.add(faction.asString()));
            if (set.equals(factionSet)
                    && cohort.path("seatIndex").isNumber()
                    && cohort.path("seatIndex").asInt() == seatIndex) {
                return cohort;
            }
        }
        throw new AssertionError("no cohort for " + factionSet + " seat " + seatIndex);
    }

    private static JsonNode pooled(JsonNode comparison) {
        for (var cohort : comparison.path("cohorts")) {
            if (cohort.path("factionSet").isNull() && cohort.path("seatIndex").isNull()) {
                return cohort;
            }
        }
        throw new AssertionError("no pooled cohort");
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

    /** The CSV row of a metric of the given faction in the cohort that pools every set at one seat of 3 players. */
    private static java.util.Map<String, String> csvRow(String csv, String metric, String faction, String seatIndex) {
        var lines = csv.split("\r\n");
        var header = parse(lines[0]);
        for (var index = 1; index < lines.length; index++) {
            var cells = parse(lines[index]);
            var row = new java.util.LinkedHashMap<String, String>();
            for (var column = 0; column < header.size(); column++) {
                row.put(header.get(column), cells.get(column));
            }
            if (metric.equals(row.get("metric"))
                    && faction.equals(row.get("faction"))
                    && row.get("quantile").isEmpty()
                    && seatIndex.equals(row.get("seatIndex"))
                    && "3".equals(row.get("playerCount"))
                    && row.get("factionSet").contains("PROPHETS")) {
                return row;
            }
        }
        throw new AssertionError("no CSV row for " + metric + " " + faction);
    }

    private static List<String> parse(String line) {
        var cells = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < line.length(); index++) {
            var c = line.charAt(index);
            if (quoted) {
                if (c == '"' && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
