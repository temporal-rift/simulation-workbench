package io.github.temporalrift.workbench.execution;

import java.util.List;
import java.util.UUID;

/**
 * One logical case of a run: its matrix coordinate, its state, and its single reconciled result once it
 * succeeded.
 *
 * @param state one of {@code PENDING}, {@code RUNNING}, {@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED}
 * @param result present only when the case succeeded
 * @param failureCode the code of the failed case's final failed attempt; null unless the case failed
 */
public record RunCase(
        UUID caseId,
        String variantLabel,
        String seed,
        int playerCount,
        List<Seat> seats,
        String state,
        Result result,
        String failureCode) {

    public RunCase {
        seats = List.copyOf(seats);
    }

    public RunCase(
            UUID caseId,
            String variantLabel,
            String seed,
            int playerCount,
            List<Seat> seats,
            String state,
            Result result) {
        this(caseId, variantLabel, seed, playerCount, seats, state, result, null);
    }

    /** A seat of the case: its faction and the policy that plays it. */
    public record Seat(int seatIndex, String faction, String policyId, String policyVersion) {}

    /** The authoritative ending of the case's game. */
    public record Result(
            String endReason, List<Integer> winnerSeats, List<Score> finalScores, int eras, int rounds, int decisions) {

        public Result {
            winnerSeats = List.copyOf(winnerSeats);
            finalScores = List.copyOf(finalScores);
        }
    }

    /** A seat's final score. */
    public record Score(int seatIndex, int score) {}
}
