package io.github.temporalrift.workbench.analysis.application.query;

import io.github.temporalrift.workbench.analysis.application.port.in.ListComparisonsUseCase;
import io.github.temporalrift.workbench.analysis.domain.port.out.ComparisonRepository;
import io.github.temporalrift.workbench.shared.Page;

public class ListComparisonsQueryHandler implements ListComparisonsUseCase {

    private final ComparisonRepository comparisons;

    public ListComparisonsQueryHandler(ComparisonRepository comparisons) {
        this.comparisons = comparisons;
    }

    @Override
    public Page<Listed> handle(Query query) {
        var items = comparisons.list(query.runId(), query.limit(), query.offset()).stream()
                .map(stored -> new Listed(stored.definition(), stored.createdAt()))
                .toList();
        return new Page<>(items, comparisons.count(query.runId()));
    }
}
