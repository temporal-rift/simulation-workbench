package io.github.temporalrift.workbench.execution;

/** The execution module refused a request from another module, for the reason the message names. */
public class ExecutionRefusedException extends RuntimeException {

    public ExecutionRefusedException(String message) {
        super(message);
    }

    public ExecutionRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
