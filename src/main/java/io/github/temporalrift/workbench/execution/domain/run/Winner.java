package io.github.temporalrift.workbench.execution.domain.run;

import io.github.temporalrift.workbench.shared.game.Faction;

/** An authoritative winner by seat and faction; {@code winType} is absent for the special endings. */
public record Winner(int seatIndex, Faction faction, WinType winType) {}
