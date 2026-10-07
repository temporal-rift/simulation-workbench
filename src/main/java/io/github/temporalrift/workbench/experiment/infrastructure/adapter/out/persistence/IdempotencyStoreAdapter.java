package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;

/** JPA-backed idempotency claims. Keys are insert-only; the primary key wins races. */
public class IdempotencyStoreAdapter implements IdempotencyStore {

    private final IdempotencyKeyJpaRepository repository;

    public IdempotencyStoreAdapter(IdempotencyKeyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Claim> findByKey(UUID key) {
        return repository
                .findById(key)
                .map(entity ->
                        new Claim(entity.key(), entity.requestHash(), entity.experimentId(), entity.createdAt()));
    }

    @Override
    public void claim(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
        // saveAndFlush — never merge — so a duplicate key surfaces as a constraint violation
        // inside the caller's transaction instead of at some later commit.
        repository.saveAndFlush(new IdempotencyKeyEntity(key, requestHash, experimentId, createdAt));
    }
}
