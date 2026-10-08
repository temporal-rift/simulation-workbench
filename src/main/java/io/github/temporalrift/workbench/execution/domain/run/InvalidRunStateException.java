package io.github.temporalrift.workbench.execution.domain.run;

/** A run command is not legal in the run's current state (published as {@code INVALID_RUN_STATE}). */
public class InvalidRunStateException extends RuntimeException {

    private final RunState state;

    public InvalidRunStateException(String operation, RunState state) {
        super(operation + " is not legal for a run in state " + state);
        this.state = state;
    }

    public RunState state() {
        return state;
    }
}
