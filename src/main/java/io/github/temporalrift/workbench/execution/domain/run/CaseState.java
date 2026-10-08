package io.github.temporalrift.workbench.execution.domain.run;

/** State of a logical case; SUCCEEDED, FAILED and CANCELLED are final. */
public enum CaseState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
