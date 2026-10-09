package io.github.temporalrift.workbench.analysis.domain.game;

/** Where a logical case stands; only a succeeded case is a game. */
public enum CaseStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
