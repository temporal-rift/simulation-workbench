package io.github.temporalrift.workbench.experiment.domain;

/** Raised when a manifest fails validation, carrying the contract error code. */
public class ExperimentValidationException extends RuntimeException {

    private final ExperimentErrorCode code;

    public ExperimentValidationException(ExperimentErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ExperimentErrorCode code() {
        return code;
    }
}
