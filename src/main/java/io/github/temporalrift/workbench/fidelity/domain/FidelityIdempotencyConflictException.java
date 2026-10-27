package io.github.temporalrift.workbench.fidelity.domain;

/** An Idempotency-Key was reused for another seed (published as IDEMPOTENCY_CONFLICT). */
public class FidelityIdempotencyConflictException extends RuntimeException {

    public FidelityIdempotencyConflictException() {
        super("Idempotency-Key was reused with a different request");
    }
}
