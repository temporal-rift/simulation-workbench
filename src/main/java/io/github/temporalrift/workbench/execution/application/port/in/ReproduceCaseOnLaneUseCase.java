package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;

/**
 * Requests an exact reproduction of a saved case on a named lane other than the one that played it, when that lane is
 * build-equivalent to it. The same Idempotency-Key returns the original reproduction, whatever state it has reached.
 */
public interface ReproduceCaseOnLaneUseCase {

    /**
     * @throws io.github.temporalrift.workbench.execution.domain.reproduction.LaneNotEquivalentException when the lane
     *     is not configured or differs from the lane that played the case
     * @throws io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException when the
     *     pinned artifacts are missing or no longer match
     */
    Reproduction handle(Command command);

    record Command(UUID runId, UUID caseId, String laneId, UUID idempotencyKey) {}
}
