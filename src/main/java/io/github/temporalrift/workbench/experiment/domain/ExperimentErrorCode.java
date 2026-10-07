package io.github.temporalrift.workbench.experiment.domain;

/** Stable error codes for the experiment boundary, matching the published contract vocabulary. */
public enum ExperimentErrorCode {
    INVALID_EXPERIMENT,
    MANIFEST_MISMATCH,
    EXPERIMENT_IMMUTABLE,
    IDEMPOTENCY_CONFLICT
}
