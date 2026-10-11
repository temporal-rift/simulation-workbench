package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "run")
class RunJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "concurrency", nullable = false)
    private int concurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    protected RunJpaEntity() {}

    RunJpaEntity(UUID runId, UUID experimentId, String state, int concurrency, Instant createdAt) {
        this.runId = runId;
        this.experimentId = experimentId;
        this.state = state;
        this.concurrency = concurrency;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return runId;
    }

    void changeState(String state) {
        this.state = state;
    }

    void transition(String state, Instant startedAt, Instant finishedAt, String failureCode, String failureMessage) {
        this.state = state;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    UUID runId() {
        return runId;
    }

    UUID experimentId() {
        return experimentId;
    }

    String state() {
        return state;
    }

    int concurrency() {
        return concurrency;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    String failureCode() {
        return failureCode;
    }

    String failureMessage() {
        return failureMessage;
    }
}
