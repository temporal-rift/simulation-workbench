package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.scoring.BetRates;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModelResult;

/** Plays the scoring layer of a design from per-era bet rates and sweeps the sensitivity of its win ratios. */
public interface EvaluateScoringModelUseCase {

    ScoringModelResult handle(Query query);

    /**
     * @param gamesPerPlayerCount the games to play per player count, or {@code null} for the default
     * @param sweep the values to move one step each way
     */
    record Query(
            ScoringEngine engine,
            List<Integer> playerCounts,
            List<BetRates> rates,
            Integer gamesPerPlayerCount,
            long seed,
            List<SweepParameter> sweep) {}

    /** A value to move, as a path, and the signed step to move it by. */
    record SweepParameter(String parameter, double step) {}
}
