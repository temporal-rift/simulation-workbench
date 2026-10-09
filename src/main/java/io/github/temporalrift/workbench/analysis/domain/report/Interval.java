package io.github.temporalrift.workbench.analysis.domain.report;

/** A 95% block-bootstrap percentile interval. */
public record Interval(double lower, double upper) {}
