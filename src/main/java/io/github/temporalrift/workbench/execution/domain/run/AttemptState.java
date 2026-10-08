package io.github.temporalrift.workbench.execution.domain.run;

/** State of one execution or recovery of a logical case. */
public enum AttemptState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
