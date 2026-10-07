package io.github.temporalrift.workbench.policy.domain.decision;

/**
 * The accepted state could not be established, so the submission may or may not have been spent.
 * The caller must reconcile again and never resubmit blindly.
 */
public class ReconciliationPendingException extends RuntimeException {

    public ReconciliationPendingException(String message) {
        super(message);
    }
}
