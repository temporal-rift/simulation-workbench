package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringResult;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;

/** Re-scores the recorded bet outcomes of a run's cases under another scoring engine, storing nothing. */
public interface RescoreRunUseCase {

    RescoringResult handle(Query query);

    record Query(UUID runId, ScoringEngine engine, String variantLabel, String tableId) {}
}
