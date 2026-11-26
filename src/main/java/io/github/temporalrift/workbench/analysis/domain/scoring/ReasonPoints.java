package io.github.temporalrift.workbench.analysis.domain.scoring;

/**
 * Points one score reason counts in a re-scored game: a reason the run's variant configures that settles no
 * faction's bet.
 *
 * @param scoreReason a score reason the run's variant configures that settles no faction's bet
 * @param points points each listed occurrence of the reason counts in the re-scored game
 */
public record ReasonPoints(String scoreReason, int points) {}
