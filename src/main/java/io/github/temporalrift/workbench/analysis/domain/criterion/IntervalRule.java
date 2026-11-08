package io.github.temporalrift.workbench.analysis.domain.criterion;

/**
 * How a line's 95% interval is computed: the percentile block bootstrap over the cohort's seeds, or, for a line whose
 * value is a share of games or cases, the union of that interval and the two-sided Clopper–Pearson interval on the
 * cohort's pooled numerator and denominator.
 */
public enum IntervalRule {
    BLOCK_BOOTSTRAP,
    BLOCK_BOOTSTRAP_UNION_CLOPPER_PEARSON
}
