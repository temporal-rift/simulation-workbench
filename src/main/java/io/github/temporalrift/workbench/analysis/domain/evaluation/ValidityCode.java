package io.github.temporalrift.workbench.analysis.domain.evaluation;

/** Why a run cannot be evaluated as evidence about the rules. */
public enum ValidityCode {
    UNFINISHED_CASES,
    CONTRACT_MISMATCH,
    CONFIGURATION_DRIFT,
    EXECUTION_FAILED_SHARE,
    ALL_PLAYERS_ABANDONED
}
