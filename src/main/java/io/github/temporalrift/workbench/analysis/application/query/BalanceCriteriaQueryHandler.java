package io.github.temporalrift.workbench.analysis.application.query;

import java.util.List;

import io.github.temporalrift.workbench.analysis.application.port.in.GetBalanceCriterionUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.ListBalanceCriteriaUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;

/** Serves the configured balance criterion versions. */
public class BalanceCriteriaQueryHandler implements ListBalanceCriteriaUseCase, GetBalanceCriterionUseCase {

    private final BalanceCriteria criteria;

    public BalanceCriteriaQueryHandler(BalanceCriteria criteria) {
        this.criteria = criteria;
    }

    @Override
    public List<BalanceCriterion> handle() {
        return criteria.all();
    }

    @Override
    public BalanceCriterion handle(int version) {
        return criteria.find(version)
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Balance criterion", version));
    }
}
