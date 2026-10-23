package io.github.temporalrift.workbench.execution.domain.run;

import io.github.temporalrift.workbench.shared.game.Faction;

/** A seat's authoritative final score. */
public record FinalScore(int seatIndex, Faction faction, int score) {}
