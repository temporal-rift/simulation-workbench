package io.github.temporalrift.workbench.fidelity.domain;

/** Lifecycle of a check; {@link #PASSED} and {@link #FAILED} never change again. */
public enum FidelityCheckState {
    QUEUED,
    RUNNING,
    PASSED,
    FAILED;

    /** Whether the check can still end: only one check may be open at a time. */
    public boolean isOpen() {
        return this == QUEUED || this == RUNNING;
    }
}
