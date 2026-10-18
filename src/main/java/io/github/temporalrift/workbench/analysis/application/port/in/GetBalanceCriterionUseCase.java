package io.github.temporalrift.workbench.analysis.application.port.in;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;

/** Reads one balance criterion version. */
public interface GetBalanceCriterionUseCase {

    /**
     * @throws io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException for a version the
     *     workbench does not serve
     */
    BalanceCriterion handle(int version);
}
