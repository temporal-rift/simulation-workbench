package io.github.temporalrift.workbench.execution.domain.run;

/** The attempt cannot continue for a recorded reason; the case may be retried on a fresh attempt. */
public class AttemptFailedException extends RuntimeException {

    private final transient Failure failure;

    public AttemptFailedException(FailureCode code, String message) {
        this(code, message, null);
    }

    public AttemptFailedException(FailureCode code, String message, Throwable cause) {
        super(message, cause);
        this.failure = new Failure(code, message);
    }

    public Failure failure() {
        return failure;
    }
}
