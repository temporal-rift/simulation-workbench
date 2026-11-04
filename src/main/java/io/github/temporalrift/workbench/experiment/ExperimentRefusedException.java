package io.github.temporalrift.workbench.experiment;

/** The experiment module refused a request from another module, for the reason the message names. */
public class ExperimentRefusedException extends RuntimeException {

    private final String reason;

    public ExperimentRefusedException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * @param reason why the rules were refused, as the published contract names it, or null when the refusal is
     *     not about a baseline, a parameter space or an override
     */
    public ExperimentRefusedException(String message, String reason, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** Why the rules were refused, as the published contract names it; null when it is not about the rules. */
    public String reason() {
        return reason;
    }
}
