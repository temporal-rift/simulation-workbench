package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Optional;

/**
 * The simulator is not enabled, no real lane is build-equivalent to it, or its baseline rules cannot carry the check's
 * variants (published as SIMULATOR_NOT_AVAILABLE).
 */
public class SimulatorNotAvailableException extends RuntimeException {

    private final String refusalReason;

    public SimulatorNotAvailableException(String message) {
        this(message, null);
    }

    /**
     * @param refusalReason why the baseline rules were refused, as the published contract names it (for example
     *     {@code UNKNOWN_BASELINE}), or null when the simulator is unavailable for another reason
     */
    public SimulatorNotAvailableException(String message, String refusalReason) {
        super(message);
        this.refusalReason = refusalReason;
    }

    public Optional<String> refusalReason() {
        return Optional.ofNullable(refusalReason);
    }
}
