package io.github.temporalrift.workbench.analysis.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonDefinition;

/** Stored comparison definitions and the idempotency keys that created them. */
public interface ComparisonRepository {

    Optional<ComparisonDefinition> find(UUID comparisonId);

    Optional<Claim> findByKey(UUID idempotencyKey);

    /** Stores the definition under the key unless the key is already taken; returns whether it was stored. */
    boolean create(UUID idempotencyKey, String requestHash, ComparisonDefinition definition);

    /** Comparisons newest first, optionally those with a side in the given run; {@code offset} skips that many. */
    List<Stored> list(UUID runId, int limit, int offset);

    /** How many comparisons {@link #list} matches. */
    long count(UUID runId);

    /** A stored comparison definition with the time it was created. */
    record Stored(ComparisonDefinition definition, Instant createdAt) {}

    /** A comparison created under an idempotency key, with the hash of the request that created it. */
    record Claim(String requestHash, ComparisonDefinition definition) {}
}
