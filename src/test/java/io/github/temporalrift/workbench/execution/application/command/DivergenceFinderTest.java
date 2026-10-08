package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;

class DivergenceFinderTest {

    private static final String WINDOW = "era1/hand-selection";
    private static final String DIGEST = "d".repeat(64);

    @Test
    void identicalAcceptedStepsAndEndingsDoNotDiverge() {
        var steps = List.of(step(0, 0, "{\"a\":1}", "keep:x"), step(1, 1, "{\"a\":2}", "keep:y"));

        assertThat(DivergenceFinder.first(steps, steps, result(), result())).isEmpty();
    }

    @Test
    void rejectionsAndLostAcknowledgementsAreTransportHistoryAndDoNotCount() {
        var saved = List.of(
                step(0, 0, "{\"a\":1}", "keep:rejected", StepOutcome.REJECTED),
                step(1, 0, "{\"a\":1}", "keep:x", StepOutcome.ACCEPTED));
        var again = List.of(step(0, 0, "{\"a\":1}", "keep:x", StepOutcome.ACCEPTED));

        assertThat(DivergenceFinder.first(saved, again, result(), result())).isEmpty();
    }

    @Test
    void aDifferentObservationIsTheFirstDivergenceAtThatStep() {
        var saved = List.of(step(0, 0, "{\"a\":1}", "keep:x"), step(1, 1, "{\"a\":2}", "keep:y"));
        var again = List.of(step(0, 0, "{\"a\":1}", "keep:x"), step(1, 1, "{\"a\":3}", "keep:y"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence.step()).isEqualTo(1);
        assertThat(divergence.kind()).isEqualTo("OBSERVATION");
        assertThat(divergence.expected()).containsEntry("seatIndex", 1).containsEntry("observation", Map.of("a", 2));
        assertThat(divergence.actual()).containsEntry("observation", Map.of("a", 3));
    }

    @Test
    void aDifferentAcceptedDecisionIsADecisionDivergence() {
        var saved = List.of(step(0, 0, "{\"a\":1}", "keep:x"));
        var again = List.of(step(0, 0, "{\"a\":1}", "keep:z"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence.kind()).isEqualTo("DECISION");
        assertThat(divergence.expected()).containsEntry("decision", "keep:x");
        assertThat(divergence.actual()).containsEntry("decision", "keep:z");
    }

    @Test
    void aDifferentSeatOrWindowIsAWindowDivergence() {
        var saved = List.of(step(0, 0, "{}", "keep:x"));
        var again = List.of(step(0, 1, "{}", "keep:x"));

        assertThat(DivergenceFinder.first(saved, again, result(), result())
                        .orElseThrow()
                        .kind())
                .isEqualTo("WINDOW");
    }

    @Test
    void aReproductionThatStopsEarlyMissesTheStepTheCaseHad() {
        var saved = List.of(step(0, 0, "{}", "keep:x"), step(1, 1, "{}", "keep:y"));
        var again = List.of(step(0, 0, "{}", "keep:x"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence.step()).isEqualTo(1);
        assertThat(divergence.kind()).isEqualTo("MISSING_STEP");
    }

    @Test
    void aReproductionThatGoesOnAddsAStepTheCaseDidNotHave() {
        var saved = List.of(step(0, 0, "{}", "keep:x"));
        var again = List.of(step(0, 0, "{}", "keep:x"), step(1, 1, "{}", "keep:y"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence.step()).isEqualTo(1);
        assertThat(divergence.kind()).isEqualTo("UNEXPECTED_STEP");
    }

    @Test
    void whenEveryStepAgreesTheEndingIsCompared() {
        var steps = List.of(step(0, 0, "{}", "keep:x"), step(1, 1, "{}", "keep:y"));

        var reason = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.TIMELINE_COLLAPSED, winner(0), 30, DIGEST));
        var winners = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.WIN_CONDITION_MET, winner(1), 30, DIGEST));
        var scores = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.WIN_CONDITION_MET, winner(0), 31, DIGEST));
        var digest = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.WIN_CONDITION_MET, winner(0), 30, "e".repeat(64)));

        assertThat(reason.orElseThrow().kind()).isEqualTo("END_REASON");
        assertThat(winners.orElseThrow().kind()).isEqualTo("WINNERS");
        assertThat(scores.orElseThrow().kind()).isEqualTo("FINAL_SCORES");
        assertThat(digest.orElseThrow().kind()).isEqualTo("SEMANTIC_DIGEST");
        assertThat(reason.orElseThrow().step()).isEqualTo(2);
    }

    private static StepRecord step(int step, int seat, String observation, String decision) {
        return step(step, seat, observation, decision, StepOutcome.ACCEPTED);
    }

    private static StepRecord step(int step, int seat, String observation, String decision, StepOutcome outcome) {
        return new StepRecord(
                step,
                seat,
                WINDOW,
                "HAND_SELECTION",
                1,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                observation,
                decision,
                outcome,
                null,
                null);
    }

    private static Winner winner(int seat) {
        return new Winner(seat, "ERASERS", WinType.SCORE_THRESHOLD);
    }

    private static CaseResult result() {
        return result(EndReason.WIN_CONDITION_MET, winner(0), 30, DIGEST);
    }

    private static CaseResult result(EndReason reason, Winner winner, int score, String digest) {
        return new CaseResult(reason, List.of(winner), List.of(new FinalScore(0, "ERASERS", score)), 3, 2, 2, digest);
    }
}
