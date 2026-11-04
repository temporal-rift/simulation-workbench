package io.github.temporalrift.workbench.search.domain.campaign;

/** Confirmation of a short list on fresh seeds is not available yet. */
public class ConfirmationNotAvailableException extends RuntimeException {

    public ConfirmationNotAvailableException() {
        super("Confirming a campaign short list on fresh seeds is not available yet");
    }
}
