package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;
import java.util.Set;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * One faction's scoring engine: the n-th win of the current win run scores {@code winPoints[n-1]} (the last value
 * repeats), a miss scores {@code missPoints}, and the outcomes in {@code resetOn} reset the win run.
 *
 * @param objectiveWins the total win count that earns {@code objectiveBonus} once, or {@code null} for none
 */
public record FactionScoring(
        Faction faction,
        List<Integer> winPoints,
        int missPoints,
        Set<BetOutcome> resetOn,
        Integer objectiveWins,
        int objectiveBonus) {

    public FactionScoring {
        winPoints = List.copyOf(winPoints);
        resetOn = Set.copyOf(resetOn);
    }
}
