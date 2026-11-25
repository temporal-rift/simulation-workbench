package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

/** A validated scoring-layer game: an engine, the rates of each player count and faction, and how much to play. */
public record ScoringModel(
        ScoringEngine engine, List<Integer> playerCounts, List<BetRates> rates, int gamesPerPlayerCount, long seed) {

    public ScoringModel {
        playerCounts = List.copyOf(playerCounts);
        rates = List.copyOf(rates);
    }
}
