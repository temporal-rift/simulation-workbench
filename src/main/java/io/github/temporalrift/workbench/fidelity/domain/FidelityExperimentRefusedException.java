package io.github.temporalrift.workbench.fidelity.domain;

/** The experiment module refused to prepare or freeze the experiment of a check. */
public class FidelityExperimentRefusedException extends RuntimeException {

    public FidelityExperimentRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
