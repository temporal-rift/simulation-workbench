package io.github.temporalrift.workbench.execution;

import java.util.Optional;
import java.util.UUID;

/**
 * Reproduces a saved case on a lane the caller names, which {@code reproduceCase} never does: that operation always
 * uses the lane that played the case.
 */
public interface CaseReproducer {

    /**
     * Queues one exact reproduction of a succeeded case on {@code laneId}, once per idempotency key. The lane must be
     * build-equivalent to the lane that played the case for the case's number of seats.
     *
     * @throws ExecutionRefusedException when the lane is not configured or differs from the lane that played the
     *     case, or the case's pinned artifacts are missing or no longer match
     */
    ReproductionOutcome reproduceOn(UUID runId, UUID caseId, String laneId, UUID idempotencyKey);

    /** The reproduction as it stands, or empty when it does not exist. */
    Optional<ReproductionOutcome> reproduction(UUID reproductionId);
}
