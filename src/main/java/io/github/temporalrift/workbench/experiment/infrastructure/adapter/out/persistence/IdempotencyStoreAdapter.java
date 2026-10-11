package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

/** JPA-backed idempotency claims; the primary key decides a race between two requests with the same key. */
public class IdempotencyStoreAdapter implements IdempotencyStore {

    private final IdempotencyKeyJpaRepository repository;
    private final InsertOnce insertOnce;

    public IdempotencyStoreAdapter(IdempotencyKeyJpaRepository repository, InsertOnce insertOnce) {
        this.repository = repository;
        this.insertOnce = insertOnce;
    }

    @Override
    public Optional<Claim> findByKey(UUID key) {
        return repository
                .findById(key)
                .map(entity ->
                        new Claim(entity.key(), entity.requestHash(), entity.experimentId(), entity.createdAt()));
    }

    @Override
    public boolean saveIfAbsent(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
        return !repository.existsById(key)
                && insertOnce.insert(() ->
                        repository.saveAndFlush(new IdempotencyKeyEntity(key, requestHash, experimentId, createdAt)));
    }
}
