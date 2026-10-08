package io.github.temporalrift.workbench.execution.domain.reproduction;

public enum ReproductionState {
    QUEUED,
    RUNNING,
    /** The reproduction produced the same semantic setup, decisions, outcome, scoring and result. */
    MATCH,
    /** The reproduction ran to the end and differed; the first divergence says where. */
    DIVERGED,
    /** The reproduction could not run to an end, so it says nothing about the case. */
    FAILED;

    public boolean isFinal() {
        return this == MATCH || this == DIVERGED || this == FAILED;
    }
}
