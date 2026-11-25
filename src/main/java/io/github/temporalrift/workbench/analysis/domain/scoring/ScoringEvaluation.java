package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.Faction;

/** The win ratios and race endings of one evaluation of a scoring model. */
public record ScoringEvaluation(List<Cell> cells, List<Ending> endings) {

    public ScoringEvaluation {
        cells = List.copyOf(cells);
        endings = List.copyOf(endings);
    }

    /** A faction's win ratio at one player count, with its 95% interval. */
    public record Cell(int playerCount, Faction faction, double ratio, double lower, double upper) {}

    /** How the races of one player count ended. */
    public record Ending(int playerCount, double meanEras, double thresholdShare, double finalEraShare) {}
}
