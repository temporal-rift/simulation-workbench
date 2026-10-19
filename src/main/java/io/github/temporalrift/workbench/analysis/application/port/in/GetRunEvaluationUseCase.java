package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluation;

/** Derives the evaluation of one run variant against one criterion version from the run's current cases. */
public interface GetRunEvaluationUseCase {

    RunEvaluation handle(Query query);

    record Query(UUID runId, String variantLabel, int criterionVersion, EvaluationMode mode) {}
}
