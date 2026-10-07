package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "idempotency_key")
class IdempotencyKeyEntity implements Persistable<UUID> {

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected IdempotencyKeyEntity() {}

    IdempotencyKeyEntity(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.experimentId = experimentId;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
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
