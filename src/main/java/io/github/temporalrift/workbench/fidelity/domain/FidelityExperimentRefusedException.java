package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Optional;

/** The experiment module refused to prepare or freeze the experiment of a check. */
public class FidelityExperimentRefusedException extends RuntimeException {

    private final String reason;

    public FidelityExperimentRefusedException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * @param reason why the rules were refused, as the published contract names it (for example
     *     {@code UNKNOWN_BASELINE}), or null when the refusal is not about the rules
     */
    public FidelityExperimentRefusedException(String message, String reason, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }
}
