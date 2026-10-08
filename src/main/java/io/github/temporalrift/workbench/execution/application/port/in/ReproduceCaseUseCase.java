package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;

/**
 * Requests an exact reproduction of a saved case. The same Idempotency-Key returns the original
 * reproduction, whatever state it has reached since.
 */
public interface ReproduceCaseUseCase {

    /**
     * @throws io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException when
     *     the pinned artifacts are missing or no longer match
     */
    Reproduction handle(Command command);

    record Command(UUID runId, UUID caseId, UUID idempotencyKey) {}
}
