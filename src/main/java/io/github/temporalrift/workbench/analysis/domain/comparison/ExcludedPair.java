package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;

/** A pair kept out of the paired estimates, visible with both cases and why. */
public record ExcludedPair(
        String seed,
        int playerCount,
        String policyId,
        String policyVersion,
        List<Faction> seats,
        UUID baselineCaseId,
        UUID candidateCaseId,
        CaseStatus baselineState,
        CaseStatus candidateState,
        ExclusionReason reason) {

    public ExcludedPair {
        seats = List.copyOf(seats);
    }
}
