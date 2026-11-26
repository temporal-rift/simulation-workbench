package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A validated scoring-layer game: an engine, the factions at the table, the rates of each player count and faction,
 * and how much to play.
 */
public record ScoringModel(
        ScoringEngine engine,
        List<Faction> factions,
        List<Integer> playerCounts,
        List<SeatEraRates> rates,
        int gamesPerPlayerCount,
        long seed) {

    public ScoringModel {
        factions = List.copyOf(factions);
        playerCounts = List.copyOf(playerCounts);
        rates = List.copyOf(rates);
    }
}
