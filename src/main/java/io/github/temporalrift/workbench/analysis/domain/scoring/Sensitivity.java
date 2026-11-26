package io.github.temporalrift.workbench.analysis.domain.scoring;

import io.github.temporalrift.workbench.shared.game.Faction;

/** The change of one cell's ratio when one swept value moves by {@code delta}. */
public record Sensitivity(String parameter, double delta, int playerCount, Faction faction, double ratioChange) {}
