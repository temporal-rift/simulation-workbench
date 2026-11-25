package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

/** An evaluation together with the sensitivity of its ratios to the swept values. */
public record ScoringModelResult(ScoringEvaluation evaluation, List<Sensitivity> sensitivity) {

    public ScoringModelResult {
        sensitivity = List.copyOf(sensitivity);
    }
}
