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

import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotResolution;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

@Entity
@Table(name = "case_command")
@IdClass(CaseCommandJpaEntity.Key.class)
class CaseCommandJpaEntity extends AssignedIdJpaEntity<CaseCommandJpaEntity.Key> {

    /** The round column of a window that has no round, because the column is part of the key. */
    static final int NO_ROUND = 0;

    private static final String ACCEPTED = "ACCEPTED";
    private static final String RECOVERED = "RECOVERED";
    private static final String ABSENT_FROM_ACCEPTED_STATE = "ABSENT_FROM_ACCEPTED_STATE";
    private static final String REJECTED = "REJECTED";

    @Id
    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Id
    @Column(name = "seat_index", nullable = false)
    private int seatIndex;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "window_kind", nullable = false, length = 32)
    private WindowKind windowKind;

    @Id
    @Column(name = "era", nullable = false)
    private int era;

    @Id
    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "request", nullable = false, columnDefinition = "TEXT")
    private String request;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "resolution_kind", length = 32)
    private String resolutionKind;

    @Column(name = "resolution_code", length = 64)
    private String resolutionCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CaseCommandJpaEntity() {}

    CaseCommandJpaEntity(SlotId slot, UUID attemptId, String request, SlotStatus status, Instant now) {
        this.caseId = slot.caseId();
        this.seatIndex = slot.seatIndex();
        this.windowKind = slot.window().kind();
        this.era = slot.window().era();
        this.round = roundColumn(slot.window());
        this.attemptId = attemptId;
        this.request = request;
        this.status = status.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Override
    public Key getId() {
        return new Key(caseId, seatIndex, windowKind, era, round);
    }

    static int roundColumn(WindowRef window) {
        return window.round() == null ? NO_ROUND : window.round();
    }

    void resend(UUID attemptId, String request, Instant now) {
        this.attemptId = attemptId;
        this.request = request;
        this.status = SlotStatus.SENT.name();
        this.resolutionKind = null;
        this.resolutionCode = null;
        this.updatedAt = now;
    }

    void resolve(SlotResolution resolution, Instant now) {
        this.status = resolution.status().name();
        switch (resolution) {
            case SlotResolution.Accepted _ -> setResolution(ACCEPTED, null);
            case SlotResolution.Recovered _ -> setResolution(RECOVERED, null);
            case SlotResolution.AbsentFromAcceptedState _ -> setResolution(ABSENT_FROM_ACCEPTED_STATE, null);
            case SlotResolution.Rejected(var code) -> setResolution(REJECTED, code);
        }
        this.updatedAt = now;
    }

    private void setResolution(String kind, String code) {
        this.resolutionKind = kind;
        this.resolutionCode = code;
    }

    /** How the slot was settled, or null while it is in doubt. */
    SlotResolution resolution() {
        if (resolutionKind == null) {
            return null;
        }
        return switch (resolutionKind) {
            case ACCEPTED -> new SlotResolution.Accepted();
            case RECOVERED -> new SlotResolution.Recovered();
            case ABSENT_FROM_ACCEPTED_STATE -> new SlotResolution.AbsentFromAcceptedState();
            case REJECTED -> new SlotResolution.Rejected(resolutionCode);
            default -> throw new IllegalStateException("Unknown slot resolution " + resolutionKind);
        };
    }

    SlotId slot() {
        return new SlotId(caseId, seatIndex, new WindowRef(windowKind, era, round == NO_ROUND ? null : round));
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

    record Key(UUID caseId, int seatIndex, WindowKind windowKind, int era, int round) implements Serializable {

        static Key of(SlotId slot) {
            return new Key(
                    slot.caseId(),
                    slot.seatIndex(),
                    slot.window().kind(),
                    slot.window().era(),
                    roundColumn(slot.window()));
        }
    }
}
