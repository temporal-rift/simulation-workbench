package io.github.temporalrift.workbench.analysis.domain.comparison;

import io.github.temporalrift.workbench.analysis.domain.report.Dimensions;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatistic;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;

/** A metric on both sides over matched blocks only, and the candidate's difference from the baseline. */
public record MetricDifference(
        String name,
        MetricStatistic statistic,
        Dimensions dimensions,
        String unit,
        Double baselineValue,
        Double candidateValue,
        Double difference,
        MetricStatus status,
        Interval interval) {}
