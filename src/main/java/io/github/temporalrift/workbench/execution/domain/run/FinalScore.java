package io.github.temporalrift.workbench.execution.domain.run;

/** A seat's authoritative final score. */
public record FinalScore(int seatIndex, String faction, int score) {}
