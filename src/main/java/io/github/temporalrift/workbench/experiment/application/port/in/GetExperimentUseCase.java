package io.github.temporalrift.workbench.experiment.application.port.in;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Reads a frozen experiment exactly as it was frozen. */
public interface GetExperimentUseCase {

    View handle(UUID experimentId);

    record View(UUID experimentId, JsonNode manifest, String manifestDigest, Instant createdAt) {}
}
