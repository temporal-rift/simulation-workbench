package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A pair kept out of the paired estimates, visible with both cases and why.
 *
 * @param policy the policy that plays every seat; null when the table mixes policies
 */
public record ExcludedPair(
        String seed,
        String tableId,
        int playerCount,
        SeatPolicy policy,
        List<Faction> seats,
        UUID baselineCaseId,
        UUID candidateCaseId,
        CaseState baselineState,
        CaseState candidateState,
        ExclusionReason reason) {

    public ExcludedPair {
        seats = List.copyOf(seats);
    }
}
