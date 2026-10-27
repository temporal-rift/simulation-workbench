package io.github.temporalrift.workbench.execution.domain.reproduction;

/** A reproduction was asked for on a lane that is not configured or does not play the game of the case's lane. */
public class LaneNotEquivalentException extends RuntimeException {

    public LaneNotEquivalentException(String message) {
        super(message);
    }
}
