package io.github.temporalrift.workbench.analysis.domain.game;

/** Why a case failed: the code of its final failed attempt. */
public enum FailureCode {
    RUNNER_TIMEOUT,
    EXECUTION_FAILED,
    CONTRACT_MISMATCH,
    CONFIGURATION_DRIFT,
    POLICY_EXHAUSTED
}
