package io.github.temporalrift.workbench.execution;

import java.util.List;
import java.util.Map;
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
 * @param tableId the seat table whose policies play the seats
 * @param result present only when the case succeeded
 * @param failureCode the code of the failed case's final failed attempt; null unless the case failed
 */
public record RunCase(
        UUID caseId,
        UUID caseKey,
        String variantLabel,
        String tableId,
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
            String tableId,
            String seed,
            int playerCount,
            List<Seat> seats,
            CaseState state,
            Result result) {
        this(caseId, caseKey, variantLabel, tableId, seed, playerCount, seats, state, result, null);
    }

    /** A seat of the case: its faction and its policy; the digest tells apart parameter sets of one bundle. */
    public record Seat(int seatIndex, Faction faction, String policyId, String policyVersion, String policyDigest) {

        public Seat(int seatIndex, Faction faction, String policyId, String policyVersion) {
            this(seatIndex, faction, policyId, policyVersion, null);
        }
    }

    /**
     * The authoritative ending of the case's game.
     *
     * @param winTypes how each winner met the win condition, by seat; a winner of a special ending has no entry
     * @param winConditions every condition each winner met, by seat; a seat without an entry has none known
     */
    public record Result(
            EndReason endReason,
            List<Integer> winnerSeats,
            List<Score> finalScores,
            int eras,
            int rounds,
            int decisions,
            Map<Integer, String> winTypes,
            Map<Integer, List<String>> winConditions) {

        public Result {
            winnerSeats = List.copyOf(winnerSeats);
            finalScores = List.copyOf(finalScores);
            winTypes = winTypes == null ? Map.of() : Map.copyOf(winTypes);
            winConditions = winConditions == null ? Map.of() : Map.copyOf(winConditions);
        }

        /** An ending whose winners' conditions are known only through their win types. */
        public Result(
                EndReason endReason,
                List<Integer> winnerSeats,
                List<Score> finalScores,
                int eras,
                int rounds,
                int decisions,
                Map<Integer, String> winTypes) {
            this(endReason, winnerSeats, finalScores, eras, rounds, decisions, winTypes, Map.of());
        }

        /** An ending whose winners' win types are not known. */
        public Result(
                EndReason endReason,
                List<Integer> winnerSeats,
                List<Score> finalScores,
                int eras,
                int rounds,
                int decisions) {
            this(endReason, winnerSeats, finalScores, eras, rounds, decisions, Map.of(), Map.of());
        }
    }

    /** A seat's final score. */
    public record Score(int seatIndex, int score) {}
}
