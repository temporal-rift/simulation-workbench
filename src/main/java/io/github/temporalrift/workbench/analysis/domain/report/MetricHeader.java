package io.github.temporalrift.workbench.analysis.domain.report;

/** What names a metric of a cohort, apart from how its value is measured. */
public record MetricHeader(String name, MetricStatistic statistic, Dimensions dimensions, String unit) {}
