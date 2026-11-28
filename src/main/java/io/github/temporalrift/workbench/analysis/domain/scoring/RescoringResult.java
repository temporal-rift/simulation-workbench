package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

/**
 * The ratios of a run's judged cases under an engine and, per player count, how many cases were judged or truncated.
 */
public record RescoringResult(
        boolean complete,
        List<ScoringEvaluation.Cell> cells,
        List<Counts> playerCounts,
        List<Sensitivity> sensitivity) {

    public RescoringResult(boolean complete, List<ScoringEvaluation.Cell> cells, List<Counts> playerCounts) {
        this(complete, cells, playerCounts, null);
    }

    public RescoringResult {
        cells = List.copyOf(cells);
        playerCounts = List.copyOf(playerCounts);
        sensitivity = sensitivity == null ? null : List.copyOf(sensitivity);
    }

    /** @param meanEras the mean era the races ended in over the judged cases, or {@code null} without any */
    public record Counts(int playerCount, int judgedCases, int truncatedCases, Double meanEras) {}

    public enum Status {
        EVALUATED,
        OUT_OF_RANGE
    }

    public record Sensitivity(String parameter, int direction, Status status, List<SensitivityCell> cells) {
        public Sensitivity {
            cells = List.copyOf(cells);
        }
    }

    public record SensitivityCell(
            int playerCount,
            io.github.temporalrift.workbench.shared.game.Faction faction,
            double ratio,
            double delta) {}
}
