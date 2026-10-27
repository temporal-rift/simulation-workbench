package io.github.temporalrift.workbench.fidelity.domain;

/** The seed of a check is not a decimal unsigned 64-bit integer (published as INVALID_REQUEST on {@code seed}). */
public class InvalidSeedException extends IllegalArgumentException {

    public InvalidSeedException(String seed) {
        super("seed must be a decimal unsigned 64-bit integer: " + seed);
    }
}
