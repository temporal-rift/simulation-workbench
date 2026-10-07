package io.github.temporalrift.workbench.experiment.domain;

/** Raised when an idempotency key is reused with a different request representation. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
