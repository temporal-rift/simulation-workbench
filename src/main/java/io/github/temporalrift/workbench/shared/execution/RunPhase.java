package io.github.temporalrift.workbench.shared.execution;

/** Where a run stands, as other modules need to see it; {@link #isFinal()} phases never change again. */
public enum RunPhase {
    /** Queued, running, interrupted or being cancelled: it can still end. */
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this != ACTIVE;
    }
}
