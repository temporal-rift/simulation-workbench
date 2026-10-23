package io.github.temporalrift.workbench.analysis.domain.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A logical case as analysis sees it: its matrix coordinate, its status, and its outcome once it succeeded.
 * The matrix assigns one policy to every seat of a case.
 *
 * @param seatFactions the faction of each seat, indexed by seat
 * @param outcome present only when the case succeeded
 * @param failureCode present only when the case failed
 */
public record AnalyzedCase(
        UUID caseId,
        String variantLabel,
        String seed,
        int playerCount,
        String policyId,
        String policyVersion,
        List<Faction> seatFactions,
        CaseState status,
        CaseOutcome outcome,
        FailureCode failureCode) {

    public AnalyzedCase {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(status, "status");
        seatFactions = List.copyOf(seatFactions);
    }

    public AnalyzedCase(
            UUID caseId,
            String variantLabel,
            String seed,
            int playerCount,
            String policyId,
            String policyVersion,
            List<Faction> seatFactions,
            CaseState status,
            CaseOutcome outcome) {
        this(caseId, variantLabel, seed, playerCount, policyId, policyVersion, seatFactions, status, outcome, null);
    }

    /** Whether the case reached a final state that counts in a failure share. */
    public boolean finished() {
        return status == CaseState.SUCCEEDED || status == CaseState.FAILED;
    }

    public boolean succeeded() {
        return status == CaseState.SUCCEEDED;
    }
}
