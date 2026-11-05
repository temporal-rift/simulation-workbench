package io.github.temporalrift.workbench.analysis.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.RunConfirmation;
import io.github.temporalrift.workbench.analysis.application.port.in.GetRunEvaluationUseCase;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;

/** Confirms a run variant through the same evaluation the evaluation endpoint serves. */
public class RunConfirmationService implements RunConfirmation {

    private final GetRunEvaluationUseCase evaluations;
    private final BalanceCriteria criteria;

    public RunConfirmationService(GetRunEvaluationUseCase evaluations, BalanceCriteria criteria) {
        this.evaluations = evaluations;
        this.criteria = criteria;
    }

    @Override
    public int minimumSeeds(int criterionVersion) {
        return criteria.find(criterionVersion)
                .orElseThrow(() -> new IllegalArgumentException("Unknown balance criterion " + criterionVersion))
                .evidence()
                .confirmationMinimumSeeds();
    }

    @Override
    public Result confirm(int criterionVersion, UUID runId, String variantLabel) {
        try {
            var evaluation = evaluations.handle(new GetRunEvaluationUseCase.Query(
                    runId, variantLabel, criterionVersion, EvaluationMode.CONFIRMATION));
            return new Result(evaluation.verdict().name(), evaluation.score());
        } catch (io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
