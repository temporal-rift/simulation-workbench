package io.github.temporalrift.workbench.analysis.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonDefinition;

/** Stored comparison definitions and the idempotency keys that created them. */
public interface ComparisonRepository {

    Optional<ComparisonDefinition> find(UUID comparisonId);

    Optional<Claim> findByKey(UUID idempotencyKey);

    /** Stores the definition under the key unless the key is already taken; returns whether it was stored. */
    boolean create(UUID idempotencyKey, String requestHash, ComparisonDefinition definition);

    /** A comparison created under an idempotency key, with the hash of the request that created it. */
    record Claim(String requestHash, ComparisonDefinition definition) {}
}
