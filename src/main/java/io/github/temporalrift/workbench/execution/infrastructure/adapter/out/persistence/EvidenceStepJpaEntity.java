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
@Table(name = "evidence_step")
@IdClass(EvidenceStepJpaEntity.Key.class)
class EvidenceStepJpaEntity extends AssignedIdJpaEntity<EvidenceStepJpaEntity.Key> {

    @Id
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Id
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Id
    @Column(name = "step", nullable = false)
    private int step;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "seat_index", nullable = false)
    private int seatIndex;

    @Column(name = "window_key", nullable = false, length = 100)
    private String windowKey;

    @Column(name = "phase", nullable = false, length = 40)
    private String phase;

    @Column(name = "era")
    private Integer era;

    @Column(name = "round")
    private Integer round;

    @Column(name = "logical_time", nullable = false)
    private Instant logicalTime;

    @Column(name = "observation", nullable = false, columnDefinition = "TEXT")
    private String observation;

    @Column(name = "decision", nullable = false, columnDefinition = "TEXT")
    private String decision;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "outcome_code", length = 64)
    private String outcomeCode;

    @Column(name = "entropy", columnDefinition = "TEXT")
    private String entropy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected EvidenceStepJpaEntity() {}

    @SuppressWarnings("java:S107")
    EvidenceStepJpaEntity(
            UUID scopeId,
            UUID gameId,
            int step,
            UUID attemptId,
            int seatIndex,
            String windowKey,
            String phase,
            Integer era,
            Integer round,
            Instant logicalTime,
            String observation,
            String decision,
            String outcome,
            String outcomeCode,
            String entropy,
            Instant recordedAt) {
        this.scopeId = scopeId;
        this.gameId = gameId;
        this.step = step;
        this.attemptId = attemptId;
        this.seatIndex = seatIndex;
        this.windowKey = windowKey;
        this.phase = phase;
        this.era = era;
        this.round = round;
        this.logicalTime = logicalTime;
        this.observation = observation;
        this.decision = decision;
        this.outcome = outcome;
        this.outcomeCode = outcomeCode;
        this.entropy = entropy;
        this.recordedAt = recordedAt;
    }

    @Override
    public Key getId() {
        return new Key(scopeId, gameId, step);
    }

    void resolve(String outcome, String outcomeCode) {
        this.outcome = outcome;
        this.outcomeCode = outcomeCode;
    }

    int step() {
        return step;
    }

    int seatIndex() {
        return seatIndex;
    }

    String windowKey() {
        return windowKey;
    }

    String phase() {
        return phase;
    }

    Integer era() {
        return era;
    }

    Integer round() {
        return round;
    }

    Instant logicalTime() {
        return logicalTime;
    }

    String observation() {
        return observation;
    }

    String decision() {
        return decision;
    }

    String outcome() {
        return outcome;
    }

    String outcomeCode() {
        return outcomeCode;
    }

    String entropy() {
        return entropy;
    }

    record Key(UUID scopeId, UUID gameId, int step) implements Serializable {}
}
