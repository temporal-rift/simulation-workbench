package io.github.temporalrift.workbench.shared.execution;

/** Where a run stands, as other modules need to see it; {@link #isFinal()} phases never change again. */
public enum RunPhase {
    /** Queued, running or being cancelled: it is progressing or settling without anyone's help. */
    ACTIVE,
    /** Stopped by a restart or an expired lease: it makes no progress until it is resumed. */
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this != ACTIVE && this != INTERRUPTED;
    }
}
