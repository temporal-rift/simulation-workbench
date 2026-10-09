package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.Objects;
import java.util.UUID;

/** One side of a comparison: a run and one of its variants. */
public record ComparisonSide(UUID runId, String variantLabel) {

    public ComparisonSide {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(variantLabel, "variantLabel");
    }
}
