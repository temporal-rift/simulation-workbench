package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModelResult;
import io.github.temporalrift.workbench.analysis.domain.scoring.SeatEraRates;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Plays the scoring layer of a design from per-era rates of score reasons and sweeps its win ratios' sensitivity. */
public interface EvaluateScoringModelUseCase {

    ScoringModelResult handle(Query query);

    /**
     * @param factions the factions at the table; each game draws its set from their combinations
     * @param gamesPerPlayerCount the games to play per player count, or {@code null} for the default
     * @param sweep the values to move one step each way
     */
    record Query(
            ScoringEngine engine,
            List<Faction> factions,
            List<Integer> playerCounts,
            List<SeatEraRates> rates,
            Integer gamesPerPlayerCount,
            long seed,
            List<SweepParameter> sweep) {}

    /** A value to move, as a path, and the signed step to move it by. */
    record SweepParameter(String parameter, double step) {}
}
