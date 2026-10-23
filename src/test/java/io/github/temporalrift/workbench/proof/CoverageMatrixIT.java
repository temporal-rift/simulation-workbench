package io.github.temporalrift.workbench.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

/** The seed-42 coverage matrix: 220 requested cases, each ending up visibly and truthfully accounted for. */
class CoverageMatrixIT extends ProofSupport {

    private static final int REQUESTED = 220;
    private static final int PER_VARIANT_AND_POLICY = 55;

    @Test
    void everyRequestedCoordinateIsAccountedForWithItsEndingOrItsFailure() throws Exception {
        lanes.script(new ScriptedLanes.Script() {
            /** Five-player games end abnormally, without winners; every other game is decisive. */
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return context.seats().size() == 5
                        ? ScriptedLanes.withoutWinners(context, EndReason.ALL_PLAYERS_ABANDONED)
                        : ScriptedLanes.decisive(context);
            }

            @Override
            public void onOpen(CaseLane.CaseContext context) {
                if (drifts(
                        context.seats().size(),
                        context.seats().getFirst().faction().name())) {
                    throw new AttemptFailedException(FailureCode.CONFIGURATION_DRIFT, "the lane drifted");
                }
            }
        });
        var manifest = coverageManifest();
        var preview = api.preview(manifest, 500, 0);
        assertThat(preview.path("caseCount").asInt()).isEqualTo(REQUESTED);

        var runId = startRun(manifest);
        drain();

        var run = api.run(runId);
        assertThat(run.path("state").asString()).isEqualTo("COMPLETED");
        var summaries = api.caseSummaries(runId);
        assertThat(summaries).hasSize(REQUESTED);
        assertThat(summaries.stream().map(summary -> summary.path("caseKey").asString()))
                .containsExactlyElementsOf(
                        StreamSupport.stream(preview.path("cases").spliterator(), false)
                                .map(previewed -> previewed.path("caseKey").asString())
                                .toList());

        var coordinates = new HashSet<String>();
        var perGroup = new TreeMap<String, Integer>();
        var succeeded = 0;
        var failed = 0;
        for (var summary : summaries) {
            var view = api.caseOf(runId, summary.path("caseId").asString());
            coordinates.add(coordinate(view));
            perGroup.merge(view.path("variantLabel").asString() + "|" + policyOf(view), 1, Integer::sum);
            var state = view.path("state").asString();
            if ("SUCCEEDED".equals(state)) {
                succeeded++;
                assertSucceeded(view);
            } else {
                failed++;
                assertFailed(view, state);
            }
            assertThat(drifts(view.path("playerCount").asInt(), factionAt(view, 0)))
                    .as("only the coordinates the lane drifted on may fail: %s", coordinate(view))
                    .isEqualTo("FAILED".equals(state));
        }

        assertThat(coordinates).hasSize(REQUESTED);
        assertThat(perGroup)
                .hasSize(4)
                .allSatisfy((group, count) -> assertThat(count).as(group).isEqualTo(PER_VARIANT_AND_POLICY));
        assertThat(succeeded + failed).isEqualTo(REQUESTED);
        assertThat(failed).isPositive();
        var counts = run.path("counts");
        assertThat(counts.path("requested").asInt()).isEqualTo(REQUESTED);
        assertThat(counts.path("succeeded").asInt()).isEqualTo(succeeded);
        assertThat(counts.path("failed").asInt()).isEqualTo(failed);
        assertThat(counts.path("pending").asInt()).isZero();
        assertThat(counts.path("cancelled").asInt()).isZero();
    }

    @Test
    void aSingleSeedRunClaimsNoIntervalAndMarksItsReportIncomplete() throws Exception {
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public void onOpen(CaseLane.CaseContext context) {
                if (drifts(
                        context.seats().size(),
                        context.seats().getFirst().faction().name())) {
                    throw new AttemptFailedException(FailureCode.CONFIGURATION_DRIFT, "the lane drifted");
                }
            }
        });
        var runId = startRun(coverageManifest());
        drain();

        var report = api.report(runId);

        assertThat(report.path("complete").asBoolean()).isFalse();
        assertThat(report.path("caseCounts").path("requested").asInt()).isEqualTo(REQUESTED);
        var statuses = new ArrayList<String>();
        for (var cohort : report.path("cohorts")) {
            assertThat(cohort.path("independentBlocks").asInt()).isLessThanOrEqualTo(1);
            for (var metric : cohort.path("metrics")) {
                statuses.add(metric.path("status").asString());
                assertThat(metric.path("interval").isNull())
                        .as("%s of %s", metric.path("name").asString(), cohort.path("variantLabel"))
                        .isTrue();
            }
        }
        assertThat(statuses).contains("INSUFFICIENT_SAMPLE").doesNotContain("AVAILABLE");
    }

    /** The lane fails these coordinates: four-player games whose seat 0 is Activists. */
    private static boolean drifts(int playerCount, String firstSeatFaction) {
        return playerCount == 4 && "ACTIVISTS".equals(firstSeatFaction);
    }

    private static void assertSucceeded(JsonNode view) {
        var result = view.path("result");
        assertThat(result.path("endReason").asString()).isNotBlank();
        assertThat(result.path("finalScores")).hasSize(view.path("playerCount").asInt());
        assertThat(result.path("semanticDigest").asString()).matches("[a-f0-9]{64}");
        var abnormal = view.path("playerCount").asInt() == 5;
        assertThat(result.path("endReason").asString())
                .isEqualTo(abnormal ? "ALL_PLAYERS_ABANDONED" : "WIN_CONDITION_MET");
        assertThat(result.path("winners")).hasSize(abnormal ? 0 : 1);
        assertThat(view.path("attempts")).isNotEmpty();
    }

    private static void assertFailed(JsonNode view, String state) {
        assertThat(state).isEqualTo("FAILED");
        assertThat(view.path("result").isNull()).isTrue();
        var attempts = view.path("attempts");
        var failure = attempts.get(attempts.size() - 1).path("failure");
        assertThat(failure.path("code").asString()).isEqualTo("CONFIGURATION_DRIFT");
        assertThat(failure.path("message").asString()).isNotBlank();
    }

    /** Variant, policy, seed and the factions in seat order: what names a coordinate of the matrix. */
    private static String coordinate(JsonNode view) {
        var seats = new ArrayList<String>();
        view.path("assignments").forEach(seat -> seats.add(seat.path("faction").asString()));
        return String.join(
                "|",
                view.path("variantLabel").asString(),
                policyOf(view),
                view.path("seed").asString(),
                seats.toString());
    }

    private static String policyOf(JsonNode view) {
        var policies = StreamSupport.stream(view.path("assignments").spliterator(), false)
                .map(seat -> seat.path("policyId").asString() + "@"
                        + seat.path("policyVersion").asString())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        return String.join("+", policies);
    }

    private static String factionAt(JsonNode view, int seatIndex) {
        Map<Integer, String> bySeat = new TreeMap<>();
        view.path("assignments")
                .forEach(seat -> bySeat.put(
                        seat.path("seatIndex").asInt(), seat.path("faction").asString()));
        return bySeat.get(seatIndex);
    }
}
