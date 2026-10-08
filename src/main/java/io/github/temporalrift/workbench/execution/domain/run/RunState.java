package io.github.temporalrift.workbench.execution.domain.run;

/** Durable lifecycle of a run; {@link #isTerminal()} states never change again. */
public enum RunState {
    QUEUED,
    RUNNING,
    INTERRUPTED,
    CANCELLING,
    CANCELLED,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == CANCELLED || this == COMPLETED || this == FAILED;
    }
}
