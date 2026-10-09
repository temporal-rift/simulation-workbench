package io.github.temporalrift.workbench.analysis.application.port.in;

import java.time.Instant;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonDefinition;
import io.github.temporalrift.workbench.shared.Page;

/** Lists stored comparisons newest first without computing them. */
public interface ListComparisonsUseCase {

    Page<Listed> handle(Query query);

    /** A null {@code runId} leaves the filter out; otherwise a comparison matches on either of its sides. */
    record Query(UUID runId, int limit, int offset) {}

    record Listed(ComparisonDefinition definition, Instant createdAt) {}
}
