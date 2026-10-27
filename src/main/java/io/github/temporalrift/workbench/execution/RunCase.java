package io.github.temporalrift.workbench.execution;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * One logical case of a run: its matrix coordinate, its state, and its single reconciled result once it
 * succeeded.
 *
 * @param caseKey the deterministic key of the case's coordinate in the frozen experiment
 * @param result present only when the case succeeded
 * @param failureCode the code of the failed case's final failed attempt; null unless the case failed
 */
public record RunCase(
        UUID caseId,
        UUID caseKey,
        String variantLabel,
        String seed,
        int playerCount,
        List<Seat> seats,
        CaseState state,
        Result result,
        FailureCode failureCode) {

    public RunCase {
        seats = List.copyOf(seats);
    }

    public RunCase(
            UUID caseId,
            UUID caseKey,
            String variantLabel,
            String seed,
            int playerCount,
            List<Seat> seats,
            CaseState state,
            Result result) {
        this(caseId, caseKey, variantLabel, seed, playerCount, seats, state, result, null);
    }

    /** A seat of the case: its faction and the policy that plays it. */
    public record Seat(int seatIndex, Faction faction, String policyId, String policyVersion) {}

    /** The authoritative ending of the case's game. */
    public record Result(
            EndReason endReason,
            List<Integer> winnerSeats,
            List<Score> finalScores,
            int eras,
            int rounds,
            int decisions) {

        public Result {
            winnerSeats = List.copyOf(winnerSeats);
            finalScores = List.copyOf(finalScores);
        }
    }

    /** A seat's final score. */
    public record Score(int seatIndex, int score) {}
}
