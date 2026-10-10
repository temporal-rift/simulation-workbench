package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "case_attempt")
class CaseAttemptJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "game_id")
    private UUID gameId;

    @Column(name = "lane_id", length = 64)
    private String laneId;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    protected CaseAttemptJpaEntity() {}

    CaseAttemptJpaEntity(
            UUID attemptId,
            UUID caseId,
            int ordinal,
            String state,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant startedAt) {
        this.attemptId = attemptId;
        this.caseId = caseId;
        this.ordinal = ordinal;
        this.state = state;
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.startedAt = startedAt;
    }

    @Override
    public UUID getId() {
        return attemptId;
    }

    void extendLease(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    void play(UUID gameId, String laneId) {
        this.gameId = gameId;
        this.laneId = laneId;
    }

    void settle(String state, Instant finishedAt, String failureCode, String failureMessage) {
        this.state = state;
        this.finishedAt = finishedAt;
        this.leaseExpiresAt = null;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    UUID attemptId() {
        return attemptId;
    }

    UUID caseId() {
        return caseId;
    }

    int ordinal() {
        return ordinal;
    }

    String state() {
        return state;
    }

    UUID gameId() {
        return gameId;
    }

    String laneId() {
        return laneId;
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
