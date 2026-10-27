package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Objects;

/** Why a check or a case did not complete, as a code and a message. */
public record FidelityFailure(String code, String message) {

    public FidelityFailure {
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
