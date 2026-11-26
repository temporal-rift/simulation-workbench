package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.Faction;

/** The win ratios of one evaluation of a scoring model. */
public record ScoringEvaluation(List<Cell> cells) {

    public ScoringEvaluation {
        cells = List.copyOf(cells);
    }

    /** A faction's win ratio at one player count, with its 95% interval. */
    public record Cell(int playerCount, Faction faction, double ratio, double lower, double upper) {}
}
