package io.github.temporalrift.workbench.experiment;

/** The experiment module refused a request from another module, for the reason the message names. */
public class ExperimentRefusedException extends RuntimeException {

    public ExperimentRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
