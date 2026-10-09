package io.github.temporalrift.workbench.analysis.domain.report;

/** NO_DATA when nothing is in the denominator; INSUFFICIENT_SAMPLE when fewer than two seeds support a value. */
public enum MetricStatus {
    AVAILABLE,
    NO_DATA,
    INSUFFICIENT_SAMPLE
}
