package io.github.temporalrift.workbench.experiment.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Claims idempotency keys atomically: one key maps to exactly one request hash and experiment. */
public interface IdempotencyStore {

    Optional<Claim> findByKey(UUID key);

    void claim(UUID key, String requestHash, UUID experimentId, Instant createdAt);

    record Claim(UUID key, String requestHash, UUID experimentId, Instant createdAt) {}
}
