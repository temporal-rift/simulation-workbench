package io.github.temporalrift.workbench.analysis.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.application.port.in.GetComparisonUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonResult;
import io.github.temporalrift.workbench.analysis.domain.port.out.ComparisonRepository;

/** Reads a stored comparison and recomputes it from current case results. */
public class GetComparisonQueryHandler implements GetComparisonUseCase {

    private final ComparisonRepository comparisons;
    private final ComparisonEvaluator evaluator;

    public GetComparisonQueryHandler(ComparisonRepository comparisons, ComparisonEvaluator evaluator) {
        this.comparisons = comparisons;
        this.evaluator = evaluator;
    }

    @Override
    public ComparisonResult handle(UUID comparisonId) {
        return evaluator.evaluate(comparisons
                .find(comparisonId)
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Comparison", comparisonId)));
    }
}
