package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

/**
 * The ratios of a run's judged cases under an engine and, per player count, how many cases were judged or truncated.
 */
public record RescoringResult(boolean complete, List<ScoringEvaluation.Cell> cells, List<Counts> playerCounts) {

    public RescoringResult {
        cells = List.copyOf(cells);
        playerCounts = List.copyOf(playerCounts);
    }

    /** @param meanEras the mean era the races ended in over the judged cases, or {@code null} without any */
    public record Counts(int playerCount, int judgedCases, int truncatedCases, Double meanEras) {}
}
