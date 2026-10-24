package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

class DivergenceFinderTest {

    private static final WindowRef WINDOW = WindowRef.of(WindowKind.HAND_SELECTION, 1);
    private static final String DIGEST = "d".repeat(64);

    @Test
    void identicalAcceptedStepsAndEndingsDoNotDiverge() {
        var steps = List.of(step(0, 0, 1, "x"), step(1, 1, 2, "y"));

        assertThat(DivergenceFinder.first(steps, steps, result(), result())).isEmpty();
    }

    @Test
    void rejectionsAndLostAcknowledgementsAreTransportHistoryAndDoNotCount() {
        var saved = List.of(step(0, 0, 1, "rejected", StepOutcome.REJECTED), step(1, 0, 1, "x", StepOutcome.ACCEPTED));
        var again = List.of(step(0, 0, 1, "x", StepOutcome.ACCEPTED));

        assertThat(DivergenceFinder.first(saved, again, result(), result())).isEmpty();
    }

    @Test
    void aDifferentObservationIsTheFirstDivergenceAtThatStep() {
        var saved = List.of(step(0, 0, 1, "x"), step(1, 1, 2, "y"));
        var again = List.of(step(0, 0, 1, "x"), step(1, 1, 3, "y"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence.step()).isEqualTo(1);
        assertThat(divergence).isInstanceOfSatisfying(Divergence.Observation.class, observation -> {
            assertThat(observation.expected().seatIndex()).isEqualTo(1);
            assertThat(observation.expected().observation().otherPlayerIds()).containsExactly(new UUID(0, 3));
            assertThat(observation.actual().observation().otherPlayerIds()).containsExactly(new UUID(0, 4));
        });
    }

    @Test
    void aDifferentAcceptedDecisionIsADecisionDivergence() {
        var saved = List.of(step(0, 0, 1, "x"));
        var again = List.of(step(0, 0, 1, "z"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence).isInstanceOfSatisfying(Divergence.Decision.class, decision -> {
            assertThat(decision.expected().decision()).isEqualTo(keep("x"));
            assertThat(decision.actual().decision()).isEqualTo(keep("z"));
        });
    }

    @Test
    void aDifferentSeatOrWindowIsAWindowDivergence() {
        var saved = List.of(step(0, 0, 1, "x"));
        var again = List.of(step(0, 1, 1, "x"));

        assertThat(DivergenceFinder.first(saved, again, result(), result()).orElseThrow())
                .isInstanceOf(Divergence.Window.class);
    }

    @Test
    void aReproductionThatStopsEarlyMissesTheStepTheCaseHad() {
        var saved = List.of(step(0, 0, 1, "x"), step(1, 1, 1, "y"));
        var again = List.of(step(0, 0, 1, "x"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence.step()).isEqualTo(1);
        assertThat(divergence).isInstanceOf(Divergence.MissingStep.class);
    }

    @Test
    void aReproductionThatGoesOnAddsAStepTheCaseDidNotHave() {
        var saved = List.of(step(0, 0, 1, "x"));
        var again = List.of(step(0, 0, 1, "x"), step(1, 1, 1, "y"));

        var divergence =
                DivergenceFinder.first(saved, again, result(), result()).orElseThrow();

        assertThat(divergence.step()).isEqualTo(1);
        assertThat(divergence).isInstanceOf(Divergence.UnexpectedStep.class);
    }

    @Test
    void whenEveryStepAgreesTheEndingIsCompared() {
        var steps = List.of(step(0, 0, 1, "x"), step(1, 1, 1, "y"));

        var reason = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.TIMELINE_COLLAPSED, winner(0), 30, DIGEST));
        var winners = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.WIN_CONDITION_MET, winner(1), 30, DIGEST));
        var scores = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.WIN_CONDITION_MET, winner(0), 31, DIGEST));
        var digest = DivergenceFinder.first(
                steps, steps, result(), result(EndReason.WIN_CONDITION_MET, winner(0), 30, "e".repeat(64)));

        assertThat(reason.orElseThrow()).isInstanceOf(Divergence.EndReason.class);
        assertThat(winners.orElseThrow()).isInstanceOf(Divergence.Winners.class);
        assertThat(scores.orElseThrow()).isInstanceOf(Divergence.FinalScores.class);
        assertThat(digest.orElseThrow()).isInstanceOf(Divergence.SemanticDigest.class);
        assertThat(reason.orElseThrow().step()).isEqualTo(2);
    }

    private static StepRecord step(int step, int seat, int other, String decision) {
        return step(step, seat, other, decision, StepOutcome.ACCEPTED);
    }

    /** {@code other} makes the observation differ: it is the one other participant the seat sees. */
    private static StepRecord step(int step, int seat, int other, String decision, StepOutcome outcome) {
        var observation = new EntitledObservation(
                seat,
                Faction.ERASERS,
                List.of(),
                List.of(new UUID(0, other + 1)),
                new DecisionWindow.HandSelection(1, List.of(), 1));
        return new StepRecord(
                step,
                seat,
                WINDOW,
                Instant.parse("2026-01-01T00:00:00Z"),
                observation,
                keep(decision),
                outcome,
                null,
                null);
    }

    private static Candidate keep(String card) {
        return new Candidate.KeepHand(List.of(UUID.nameUUIDFromBytes(card.getBytes(StandardCharsets.UTF_8))));
    }

    private static Winner winner(int seat) {
        return new Winner(seat, Faction.ERASERS, WinType.SCORE_THRESHOLD);
    }

    private static CaseResult result() {
        return result(EndReason.WIN_CONDITION_MET, winner(0), 30, DIGEST);
    }

    private static CaseResult result(EndReason reason, Winner winner, int score, String digest) {
        return new CaseResult(
                reason, List.of(winner), List.of(new FinalScore(0, Faction.ERASERS, score)), 3, 2, 2, digest);
    }
}
