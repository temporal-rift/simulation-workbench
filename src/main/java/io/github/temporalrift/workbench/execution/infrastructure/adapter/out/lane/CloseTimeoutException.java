package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

/** A service did not answer the close of an execution context within the allowed time. */
public class CloseTimeoutException extends RuntimeException {

    public CloseTimeoutException(String laneId, Throwable cause) {
        super("Closing the execution context on lane " + laneId + " timed out", cause);
    }
}
