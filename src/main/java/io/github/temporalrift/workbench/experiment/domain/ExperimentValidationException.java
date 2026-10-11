package io.github.temporalrift.workbench.experiment.domain;

import java.util.Optional;

/** Raised when a manifest fails validation, carrying the contract error code and the offending value's path. */
public class ExperimentValidationException extends RuntimeException {

    private final ExperimentErrorCode code;
    private final String field;

    public ExperimentValidationException(ExperimentErrorCode code, String message) {
        this(code, message, null);
    }

    public ExperimentValidationException(ExperimentErrorCode code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public ExperimentErrorCode code() {
        return code;
    }

    /** Path of the offending manifest value, empty when the refusal is not attributable to one. */
    public Optional<String> field() {
        return Optional.ofNullable(field);
    }
}
