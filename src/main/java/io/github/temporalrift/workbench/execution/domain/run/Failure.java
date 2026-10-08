package io.github.temporalrift.workbench.execution.domain.run;

import java.util.Objects;

/** A recorded attempt or run failure. */
public record Failure(FailureCode code, String message) {

    public Failure {
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
