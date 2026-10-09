package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.List;

/**
 * A comparison computed from current case results.
 *
 * @param matchedBlocks matched blocks summed over the pooled cohorts
 * @param unmatchedCases pairs with a pending or running side
 * @param failedCounterparts pairs with a failed or cancelled side
 */
public record ComparisonResult(
        ComparisonDefinition definition,
        int matchedBlocks,
        int unmatchedCases,
        int failedCounterparts,
        List<ExcludedPair> excludedPairs,
        List<ComparisonCohort> cohorts) {

    public static final int FORMAT_VERSION = 1;

    public ComparisonResult {
        excludedPairs = List.copyOf(excludedPairs);
        cohorts = List.copyOf(cohorts);
    }

    /** False while any pair has a side that has not succeeded. */
    public boolean complete() {
        return excludedPairs.isEmpty();
    }
}
