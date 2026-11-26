package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

/**
 * The rules that turn score reasons into points and decide the race: flat points per reason, escalations whose
 * points grow within a run, and the era after which the highest total wins.
 */
public record ScoringEngine(int maxEras, List<ReasonPoints> reasonPoints, List<Escalation> escalations) {

    public ScoringEngine {
        reasonPoints = List.copyOf(reasonPoints);
        escalations = List.copyOf(escalations);
    }
}
