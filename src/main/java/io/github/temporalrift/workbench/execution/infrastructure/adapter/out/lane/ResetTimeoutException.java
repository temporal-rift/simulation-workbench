package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

/** A lane's reset hook did not answer within the reset timeout. */
public class ResetTimeoutException extends RuntimeException {

    public ResetTimeoutException(String laneId, Throwable cause) {
        super("Reset of lane " + laneId + " timed out", cause);
    }
}
