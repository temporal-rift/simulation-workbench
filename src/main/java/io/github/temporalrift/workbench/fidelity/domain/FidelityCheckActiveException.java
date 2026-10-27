package io.github.temporalrift.workbench.fidelity.domain;

/** Another check is still queued or running (published as FIDELITY_CHECK_ACTIVE). */
public class FidelityCheckActiveException extends RuntimeException {

    public FidelityCheckActiveException() {
        super("Another fidelity check is queued or running");
    }
}
