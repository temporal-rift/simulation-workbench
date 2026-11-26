package io.github.temporalrift.workbench.analysis.domain.scoring;

/**
 * Flat points one score reason counts per occurrence.
 *
 * @param scoreReason a score reason of the game's scoring contract
 * @param points the points of each occurrence
 */
public record ReasonPoints(String scoreReason, int points) {}
