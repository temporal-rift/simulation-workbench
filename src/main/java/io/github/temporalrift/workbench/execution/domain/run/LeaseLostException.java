package io.github.temporalrift.workbench.execution.domain.run;

/** This worker no longer owns the attempt's lease; another attempt may have recovered the case. */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException() {
        super("The attempt lease is no longer held by this worker");
    }
}
