package io.github.temporalrift.workbench.analysis.domain.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A logical case as analysis sees it: its matrix coordinate, its status, and its outcome once it succeeded.
 * The table it was played on assigns a policy to every seat.
 *
 * @param seatPolicies the policy of each seat, indexed by seat
 * @param seatFactions the faction of each seat, indexed by seat
 * @param outcome present only when the case succeeded
 * @param failureCode present only when the case failed
 */
public record AnalyzedCase(
        UUID caseId,
        String variantLabel,
        String tableId,
        String seed,
        int playerCount,
        List<SeatPolicy> seatPolicies,
        List<Faction> seatFactions,
        CaseState status,
        CaseOutcome outcome,
        FailureCode failureCode) {

    public AnalyzedCase {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(status, "status");
        seatPolicies = List.copyOf(seatPolicies);
        seatFactions = List.copyOf(seatFactions);
    }

    public AnalyzedCase(
            UUID caseId,
            String variantLabel,
            String tableId,
            String seed,
            int playerCount,
            List<SeatPolicy> seatPolicies,
            List<Faction> seatFactions,
            CaseState status,
            CaseOutcome outcome) {
        this(caseId, variantLabel, tableId, seed, playerCount, seatPolicies, seatFactions, status, outcome, null);
    }

    /** Whether the case reached a final state that counts in a failure share. */
    public boolean finished() {
        return status == CaseState.SUCCEEDED || status == CaseState.FAILED;
    }

    public boolean succeeded() {
        return status == CaseState.SUCCEEDED;
    }
}
