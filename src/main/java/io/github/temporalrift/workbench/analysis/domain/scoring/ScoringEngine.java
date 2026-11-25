package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

/** The rules that turn bet outcomes into points and decide the race. */
public record ScoringEngine(int winThreshold, int maxEras, List<FactionScoring> factions) {

    public ScoringEngine {
        factions = List.copyOf(factions);
    }
}
