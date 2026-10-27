package io.github.temporalrift.workbench.execution.domain.reproduction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;

/**
 * One exact re-execution of a saved case on one named lane. Unless the workbench itself placed it elsewhere, that is
 * the lane that played the case, whose bot identities are part of what the seats observe; {@code laneFit} is the fit
 * the lane must have to take it. It has its own identifiers and never adds a research sample: the case keeps the
 * single result it already has.
 */
public record Reproduction(
        UUID reproductionId,
        UUID attemptId,
        UUID runId,
        UUID caseId,
        String laneId,
        LaneFit laneFit,
        ReproductionState state,
        Divergence firstDivergence,
        Failure failure,
        Instant createdAt,
        Instant finishedAt) {

    public Reproduction {
        Objects.requireNonNull(reproductionId, "reproductionId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(laneFit, "laneFit");
        Objects.requireNonNull(state, "state");
    }

    public static Reproduction queued(UUID runId, UUID caseId, String laneId, LaneFit laneFit, Instant now) {
        return new Reproduction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                runId,
                caseId,
                laneId,
                laneFit,
                ReproductionState.QUEUED,
                null,
                null,
                now,
                null);
    }

    public Reproduction matched(Instant now) {
        return settled(ReproductionState.MATCH, null, null, now);
    }

    public Reproduction diverged(Divergence divergence, Instant now) {
        return settled(ReproductionState.DIVERGED, divergence, null, now);
    }

    public Reproduction failed(Failure reason, Instant now) {
        return settled(ReproductionState.FAILED, null, reason, now);
    }

    private Reproduction settled(ReproductionState next, Divergence divergence, Failure reason, Instant now) {
        return new Reproduction(
                reproductionId, attemptId, runId, caseId, laneId, laneFit, next, divergence, reason, createdAt, now);
    }
}
