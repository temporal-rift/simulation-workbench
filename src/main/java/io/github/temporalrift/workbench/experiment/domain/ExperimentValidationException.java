package io.github.temporalrift.workbench.experiment.domain;

import java.util.Optional;

/** Raised when a manifest fails validation, carrying the contract error code and the offending value's path. */
public class ExperimentValidationException extends RuntimeException {

    private final ExperimentErrorCode code;
    private final String field;
    private final VariantRulesReason reason;
    private final String constraintId;

    public ExperimentValidationException(ExperimentErrorCode code, String message) {
        this(code, message, null);
    }

    public ExperimentValidationException(ExperimentErrorCode code, String message, String field) {
        this(code, message, field, null, null);
    }

    /** A refusal of a variant's baseline or overrides; {@code constraintId} names the violated constraint, if any. */
    public ExperimentValidationException(
            ExperimentErrorCode code, String message, String field, VariantRulesReason reason, String constraintId) {
        super(message);
        this.code = code;
        this.field = field;
        this.reason = reason;
        this.constraintId = constraintId;
    }

    public ExperimentErrorCode code() {
        return code;
    }

    /** Path of the offending manifest value, empty when the refusal is not attributable to one. */
    public Optional<String> field() {
        return Optional.ofNullable(field);
    }

    /** Why the variant's rules were refused, empty for any other refusal. */
    public Optional<VariantRulesReason> reason() {
        return Optional.ofNullable(reason);
    }

    /** The violated parameter space constraint, empty unless the reason is a violated constraint. */
    public Optional<String> constraintId() {
        return Optional.ofNullable(constraintId);
    }
}
