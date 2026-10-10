package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "reproduction")
class ReproductionJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "reproduction_id", nullable = false)
    private UUID reproductionId;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "lane_id", nullable = false, length = 64)
    private String laneId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "divergence_json", columnDefinition = "TEXT")
    private String divergenceJson;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ReproductionJpaEntity() {}

    ReproductionJpaEntity(Reproduction queued, UUID idempotencyKey, String requestHash) {
        this.reproductionId = queued.reproductionId();
        this.attemptId = queued.attemptId();
        this.runId = queued.runId();
        this.caseId = queued.caseId();
        this.laneId = queued.laneId();
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.state = "QUEUED";
        this.createdAt = queued.createdAt();
    }

    @Override
    public UUID getId() {
        return reproductionId;
    }

    void claim(String owner, Instant leaseUntil) {
        this.state = "RUNNING";
        this.leaseOwner = owner;
        this.leaseExpiresAt = leaseUntil;
    }

    void extendLease(Instant leaseUntil) {
        this.leaseExpiresAt = leaseUntil;
    }

    void requeue() {
        this.state = "QUEUED";
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    void settle(String state, String divergenceJson, String failureCode, String failureMessage, Instant finishedAt) {
        this.state = state;
        this.divergenceJson = divergenceJson;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.finishedAt = finishedAt;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    UUID reproductionId() {
        return reproductionId;
    }

    UUID attemptId() {
        return attemptId;
    }

    UUID runId() {
        return runId;
    }

    UUID caseId() {
        return caseId;
    }

    String laneId() {
        return laneId;
    }

    String requestHash() {
        return requestHash;
    }

    String state() {
        return state;
    }

    String divergenceJson() {
        return divergenceJson;
    }

    String failureCode() {
        return failureCode;
    }

    String failureMessage() {
        return failureMessage;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }
}
