package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.List;

/** One stratum of a report with its own population and metrics. */
public record Cohort(
        CohortKey key, CaseCounts caseCounts, int eligibleGames, int independentBlocks, List<Metric> metrics) {

    public Cohort {
        metrics = List.copyOf(metrics);
    }
}
