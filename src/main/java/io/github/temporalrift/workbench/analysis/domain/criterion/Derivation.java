package io.github.temporalrift.workbench.analysis.domain.criterion;

/**
 * How a line takes its value from the analysis: from one metric, as a faction ratio to the win reference, or as a
 * sum of failure-share codes.
 */
public enum Derivation {
    DIRECT,
    RATIO_TO_WIN_REFERENCE,
    SUM_OF_FAILURE_CODES
}
