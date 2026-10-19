package io.github.temporalrift.workbench.analysis.domain.evaluation;

/** A screening evaluation ranks and discards candidates and never confirms; a confirmation may confirm. */
public enum EvaluationMode {
    SCREENING,
    CONFIRMATION
}
