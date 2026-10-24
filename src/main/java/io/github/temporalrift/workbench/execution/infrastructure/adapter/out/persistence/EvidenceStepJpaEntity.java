package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.infrastructure.serialization.ObservationJson;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;
import io.github.temporalrift.workbench.shared.text.JsonText;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "window_kind", nullable = false, length = 32)
    private WindowKind windowKind;

    @Column(name = "era", nullable = false)
    private int era;

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

    EvidenceStepJpaEntity(
            UUID scopeId, UUID gameId, int step, UUID attemptId, StepRecord stepRecord, Instant recordedAt) {
        this.scopeId = scopeId;
        this.gameId = gameId;
        this.step = step;
        this.attemptId = attemptId;
        this.seatIndex = stepRecord.seatIndex();
        this.windowKind = stepRecord.window().kind();
        this.era = stepRecord.window().era();
        this.round = stepRecord.window().round();
        this.logicalTime = stepRecord.logicalTime();
        this.observation = ObservationJson.of(stepRecord.observation());
        this.decision = CandidateCodec.encode(stepRecord.decision());
        this.outcome = stepRecord.outcome().name();
        this.outcomeCode = stepRecord.outcomeCode();
        this.entropy = stepRecord.entropy() == null ? null : JsonText.write(stepRecord.entropy(), "evidence");
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

    WindowRef window() {
        return new WindowRef(windowKind, era, round);
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
