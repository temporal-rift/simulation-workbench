package io.github.temporalrift.workbench.experiment.application.port.in;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Freezes a new immutable experiment manifest. */
public interface CreateExperimentUseCase {

    Result handle(Command command);

    record Command(UUID idempotencyKey, JsonNode manifest) {}

    record Result(UUID experimentId, JsonNode manifest, String manifestDigest, String createdAt) {}
}
