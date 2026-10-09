package io.github.temporalrift.workbench.analysis.domain.comparison;

/** An idempotency key was reused for another comparison request. */
public class ComparisonIdempotencyConflictException extends RuntimeException {

    public ComparisonIdempotencyConflictException() {
        super("The idempotency key was already used for another comparison request");
    }
}
