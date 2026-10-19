package io.github.temporalrift.workbench.analysis.domain.evaluation;

/** INVALID is decided before the lines: it neither rejects nor confirms, and the run is repeated. */
public enum EvaluationVerdict {
    REJECTED,
    CONFIRMED,
    UNCONFIRMED,
    INVALID
}
