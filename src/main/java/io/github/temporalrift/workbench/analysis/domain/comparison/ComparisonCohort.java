package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.report.CohortKey;

/** A cohort of the comparison; its key carries the baseline's variant label. */
public record ComparisonCohort(
        CohortKey key, int matchedBlocks, int excludedBlocks, List<MetricDifference> differences) {

    public ComparisonCohort {
        differences = List.copyOf(differences);
    }
}
