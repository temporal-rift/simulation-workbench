package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_key")
class IdempotencyKeyEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKeyEntity() {}

    IdempotencyKeyEntity(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.experimentId = experimentId;
        this.createdAt = createdAt;
    }

    UUID key() {
        return key;
    }

    String requestHash() {
        return requestHash;
    }

    UUID experimentId() {
        return experimentId;
    }

    Instant createdAt() {
        return createdAt;
    }
}
