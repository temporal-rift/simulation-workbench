package io.github.temporalrift.workbench.shared.execution;

/**
 * Why an attempt failed. An abnormal game ending is never a failure: it is an authoritative game
 * outcome carried by the case result.
 */
public enum FailureCode {
    RUNNER_TIMEOUT(true),
    EXECUTION_FAILED(true),
    CONTRACT_MISMATCH(false),
    CONFIGURATION_DRIFT(false),
    POLICY_EXHAUSTED(false);

    private final boolean retryable;

    FailureCode(boolean retryable) {
        this.retryable = retryable;
    }

    /** Whether a fresh attempt could plausibly succeed; deterministic mismatches would only repeat. */
    public boolean isRetryable() {
        return retryable;
    }
}
