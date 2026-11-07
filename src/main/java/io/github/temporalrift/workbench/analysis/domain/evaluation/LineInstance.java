package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * What one evaluated line measures, independent of the candidate measured: the criterion line, the table, policy and
 * player count of its cohort, the faction set when the cohort does not pool them, and the line's dimensions.
 */
record LineInstance(
        String lineId,
        String tableId,
        String policyId,
        int playerCount,
        List<Faction> factionSet,
        LineDimensions dimensions) {

    LineInstance {
        factionSet = factionSet == null ? null : List.copyOf(factionSet);
    }
}
