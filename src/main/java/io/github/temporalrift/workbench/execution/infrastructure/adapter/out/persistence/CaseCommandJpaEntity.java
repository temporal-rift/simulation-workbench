package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "case_command")
@IdClass(CaseCommandJpaEntity.Key.class)
class CaseCommandJpaEntity extends AssignedIdJpaEntity<CaseCommandJpaEntity.Key> {

    @Id
    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Id
    @Column(name = "seat_index", nullable = false)
    private int seatIndex;

    @Id
    @Column(name = "window_key", nullable = false, length = 100)
    private String windowKey;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "request", nullable = false, columnDefinition = "TEXT")
    private String request;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "outcome", columnDefinition = "TEXT")
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CaseCommandJpaEntity() {}

    CaseCommandJpaEntity(
            UUID caseId, int seatIndex, String windowKey, UUID attemptId, String request, String status, Instant now) {
        this.caseId = caseId;
        this.seatIndex = seatIndex;
        this.windowKey = windowKey;
        this.attemptId = attemptId;
        this.request = request;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Override
    public Key getId() {
        return new Key(caseId, seatIndex, windowKey);
    }

    void resend(UUID attemptId, String request, Instant now) {
        this.attemptId = attemptId;
        this.request = request;
        this.status = "SENT";
        this.outcome = null;
        this.updatedAt = now;
    }

    void resolve(String status, String outcome, Instant now) {
        this.status = status;
        this.outcome = outcome;
        this.updatedAt = now;
    }

    UUID caseId() {
        return caseId;
    }

    int seatIndex() {
        return seatIndex;
    }

    String windowKey() {
        return windowKey;
    }

    UUID attemptId() {
        return attemptId;
    }

    String request() {
        return request;
    }

    String status() {
        return status;
    }

    String outcome() {
        return outcome;
    }

    record Key(UUID caseId, int seatIndex, String windowKey) implements Serializable {}
}
