package io.github.temporalrift.workbench.analysis.domain.report;

/**
 * RATE is numerator over denominator; MEAN is a total over its sample size; QUANTILE is the smallest observed
 * value whose cumulative share reaches the quantile.
 */
public enum MetricStatistic {
    RATE,
    MEAN,
    QUANTILE
}
