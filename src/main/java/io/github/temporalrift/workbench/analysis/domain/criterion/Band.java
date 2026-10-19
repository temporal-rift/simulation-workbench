package io.github.temporalrift.workbench.analysis.domain.criterion;

import io.github.temporalrift.workbench.analysis.domain.report.Interval;

/**
 * A closed interval {@code [lower, upper]}, or a one-sided limit with a normalisation scale. Deviation and verdict
 * follow the criterion rule.
 */
public record Band(Double lower, Double upper, Double scale) {

    public Band {
        if (lower == null && upper == null) {
            throw new IllegalArgumentException("A band needs a lower or an upper bound");
        }
        var oneSided = lower == null || upper == null;
        if (oneSided != (scale != null)) {
            throw new IllegalArgumentException("A scale is required exactly for a one-sided band");
        }
        if (scale != null && scale <= 0) {
            throw new IllegalArgumentException("A scale must be greater than 0");
        }
    }

    /** 0 inside the band; outside it the distance to the nearer bound over the band width or scale. */
    public double deviation(double value) {
        var width = scale != null ? scale : upper - lower;
        if (lower != null && value < lower) {
            return (lower - value) / width;
        }
        if (upper != null && value > upper) {
            return (value - upper) / width;
        }
        return 0;
    }

    /** Whether the whole interval lies inside the band. */
    public boolean contains(Interval interval) {
        return (lower == null || lower <= interval.lower()) && (upper == null || interval.upper() <= upper);
    }

    /** Whether the whole interval lies outside the band, on either side. */
    public boolean excludes(Interval interval) {
        return (lower != null && interval.upper() < lower) || (upper != null && interval.lower() > upper);
    }
}
