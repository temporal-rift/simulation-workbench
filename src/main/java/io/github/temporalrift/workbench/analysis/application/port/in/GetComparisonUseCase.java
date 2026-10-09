package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonResult;

/** Reads a comparison, recomputed from current case results. */
public interface GetComparisonUseCase {

    ComparisonResult handle(UUID comparisonId);
}
