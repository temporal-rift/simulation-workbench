package io.github.temporalrift.workbench.analysis.domain.evaluation;

/** MEETS when the interval lies inside the band, FAILS when wholly outside, otherwise INCONCLUSIVE. */
public enum LineVerdict {
    MEETS,
    FAILS,
    INCONCLUSIVE
}
