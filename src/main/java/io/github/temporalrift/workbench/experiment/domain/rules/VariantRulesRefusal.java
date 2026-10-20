package io.github.temporalrift.workbench.experiment.domain.rules;

import java.util.Optional;

import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;

/**
 * A variant's baseline or overrides cannot be materialised. It does not know where the variant sits in a request, so
 * whoever does turns it into a boundary error with the full path of the offending value.
 */
public class VariantRulesRefusal extends RuntimeException {

    private final VariantRulesReason reason;
    private final String overrideKey;
    private final String constraintId;

    VariantRulesRefusal(VariantRulesReason reason, String message, String overrideKey, String constraintId) {
        super(message);
        this.reason = reason;
        this.overrideKey = overrideKey;
        this.constraintId = constraintId;
    }

    public VariantRulesReason reason() {
        return reason;
    }

    /** The override key the refusal is about, empty when it is about the overrides or the variant as a whole. */
    public Optional<String> overrideKey() {
        return Optional.ofNullable(overrideKey);
    }

    public Optional<String> constraintId() {
        return Optional.ofNullable(constraintId);
    }

    /**
     * The refusal as a boundary error. {@code variantPath} is the path of the variant's fields, such as
     * {@code variants[1].}, or empty when the request is the variant itself.
     */
    public ExperimentValidationException at(ExperimentErrorCode code, String variantPath) {
        return new ExperimentValidationException(code, getMessage(), variantPath + field(), reason, constraintId);
    }

    private String field() {
        return switch (reason) {
            case UNKNOWN_PARAMETER_SPACE -> "parameterSpaceVersion";
            case UNKNOWN_BASELINE -> "rulesArtifact.digest";
            default -> overrideKey == null ? "overrides" : "overrides." + overrideKey;
        };
    }
}
