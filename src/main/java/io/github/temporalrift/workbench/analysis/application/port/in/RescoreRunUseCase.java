package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.scoring.ReasonPoints;
import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringResult;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;

/** Re-scores the recorded bet outcomes of a run's cases under another scoring engine, storing nothing. */
public interface RescoreRunUseCase {

    RescoringResult handle(Query query);

    /**
     * @param otherReasonPoints points each named score reason counts in the re-scored game, in request order; empty
     *     keeps every other recorded score at its recorded value
     */
    record Query(
            UUID runId,
            ScoringEngine engine,
            String variantLabel,
            String tableId,
            List<ReasonPoints> otherReasonPoints) {

        public Query {
            otherReasonPoints = List.copyOf(otherReasonPoints);
        }
    }
}
