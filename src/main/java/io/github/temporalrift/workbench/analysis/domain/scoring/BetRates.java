package io.github.temporalrift.workbench.analysis.domain.scoring;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Per-era bet rates of one faction at one player count.
 *
 * @param extraPointsPerEra the mean of every other score per era, added each era
 */
public record BetRates(int playerCount, Faction faction, double winRate, double missRate, double extraPointsPerEra) {}
