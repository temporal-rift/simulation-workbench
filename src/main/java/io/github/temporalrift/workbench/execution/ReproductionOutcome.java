package io.github.temporalrift.workbench.execution;

import java.util.UUID;

import io.github.temporalrift.workbench.shared.execution.FailureCode;

/**
 * Where a reproduction stands. {@code firstDivergence} is set only when it {@code DIVERGED}, and the failure only when
 * it {@code FAILED}.
 */
public record ReproductionOutcome(
        UUID reproductionId,
        UUID runId,
        UUID caseId,
        String laneId,
        Status status,
        DivergenceReport firstDivergence,
        FailureCode failureCode,
        String failureMessage) {

    /** QUEUED and RUNNING are not final; MATCH, DIVERGED and FAILED never change again. */
    public enum Status {
        QUEUED,
        RUNNING,
        MATCH,
        DIVERGED,
        FAILED;

        public boolean isFinal() {
            return this == MATCH || this == DIVERGED || this == FAILED;
        }
    }
}
