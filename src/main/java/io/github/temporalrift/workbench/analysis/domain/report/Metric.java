package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.Objects;

/**
 * One attributed number of a cohort.
 *
 * @param numerator null for a QUANTILE
 * @param denominator the sample size of a MEAN or QUANTILE
 * @param unknownCount opportunities left out of the denominator because their playability is unknown
 */
public record Metric(
        String name,
        MetricStatistic statistic,
        Dimensions dimensions,
        String unit,
        Double value,
        Double numerator,
        long denominator,
        MetricStatus status,
        Interval interval,
        long unknownCount) {

    public Metric {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(statistic, "statistic");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(status, "status");
    }
}
