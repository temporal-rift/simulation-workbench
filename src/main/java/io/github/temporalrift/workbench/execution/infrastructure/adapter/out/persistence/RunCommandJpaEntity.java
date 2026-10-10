package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "run_command")
class RunCommandJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "operation", nullable = false, length = 16)
    private String operation;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RunCommandJpaEntity() {}

    RunCommandJpaEntity(UUID idempotencyKey, String operation, UUID runId, String requestHash, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.runId = runId;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return idempotencyKey;
    }

    UUID idempotencyKey() {
        return idempotencyKey;
    }

    String operation() {
        return operation;
    }

    UUID runId() {
        return runId;
    }

    String requestHash() {
        return requestHash;
    }
}
