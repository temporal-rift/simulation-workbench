package io.github.temporalrift.workbench.shared.execution;

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
