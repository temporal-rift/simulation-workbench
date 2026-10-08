package io.github.temporalrift.workbench.execution.domain.run;

/** An Idempotency-Key was reused for a different request. */
public class RunIdempotencyConflictException extends RuntimeException {

    public RunIdempotencyConflictException() {
        super("Idempotency-Key was reused with a different request");
    }
}
