package io.github.temporalrift.workbench.fidelity.domain;

/** The execution module refused to start the run of a check or to reproduce one of its cases. */
public class FidelityPlaybackRefusedException extends RuntimeException {

    public FidelityPlaybackRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
