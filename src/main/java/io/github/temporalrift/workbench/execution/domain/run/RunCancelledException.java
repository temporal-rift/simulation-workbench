package io.github.temporalrift.workbench.execution.domain.run;

/** The run is being cancelled; the attempt stops without recording a result or a failure. */
public class RunCancelledException extends RuntimeException {

    public RunCancelledException() {
        super("The run is being cancelled");
    }
}
