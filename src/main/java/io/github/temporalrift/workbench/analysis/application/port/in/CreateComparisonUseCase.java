package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonResult;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonSide;

/** Creates a paired comparison of two run variants, idempotently by key. */
public interface CreateComparisonUseCase {

    ComparisonResult handle(Command command);

    record Command(UUID idempotencyKey, ComparisonSide baseline, ComparisonSide candidate) {}
}
