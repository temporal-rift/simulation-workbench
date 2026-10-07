package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;

/** JPA-backed idempotency claims. A native insert-if-absent wins races without merge hazards. */
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
    @Transactional
    public boolean saveIfAbsent(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
        return repository.insertIfAbsent(key, requestHash, experimentId, createdAt) == 1;
    }
}
