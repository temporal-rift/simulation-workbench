package io.github.temporalrift.workbench.execution.domain.run;

/** An authoritative winner by seat and faction; {@code winType} is absent for the special endings. */
public record Winner(int seatIndex, String faction, WinType winType) {}
