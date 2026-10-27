package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "fidelity_case")
class FidelityCaseJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "row_id", nullable = false)
    private UUID rowId;

    @Column(name = "check_id", nullable = false)
    private UUID checkId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "case_key", nullable = false)
    private UUID caseKey;

    @Column(name = "variant_label", nullable = false, length = 16)
    private String variantLabel;

    @Column(name = "player_count", nullable = false)
    private int playerCount;

    @Column(name = "faction_set", nullable = false, length = 128)
    private String factionSet;

    @Column(name = "seat_rotation", nullable = false)
    private int seatRotation;

    @Column(name = "policy_id", nullable = false, length = 64)
    private String policyId;

    @Column(name = "run_case_id", nullable = false)
    private UUID runCaseId;

    @Column(name = "reproduction_id")
    private UUID reproductionId;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "divergence_json", columnDefinition = "TEXT")
    private String divergenceJson;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    protected FidelityCaseJpaEntity() {}

    /** The coordinate of a case and the simulator case it was played as. */
    record Coordinate(
            UUID caseKey,
            String variantLabel,
            int playerCount,
            String factionSet,
            int seatRotation,
            String policyId,
            UUID runCaseId) {}

    FidelityCaseJpaEntity(UUID rowId, UUID checkId, int ordinal, Coordinate coordinate) {
        this.rowId = rowId;
        this.checkId = checkId;
        this.ordinal = ordinal;
        this.caseKey = coordinate.caseKey();
        this.variantLabel = coordinate.variantLabel();
        this.playerCount = coordinate.playerCount();
        this.factionSet = coordinate.factionSet();
        this.seatRotation = coordinate.seatRotation();
        this.policyId = coordinate.policyId();
        this.runCaseId = coordinate.runCaseId();
        this.result = "PENDING";
    }

    @Override
    public UUID getId() {
        return rowId;
    }

    void record(UUID reproductionId, String result, String divergenceJson, String failureCode, String failureMessage) {
        this.reproductionId = reproductionId;
        this.result = result;
        this.divergenceJson = divergenceJson;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    int ordinal() {
        return ordinal;
    }

    UUID caseKey() {
        return caseKey;
    }

    String variantLabel() {
        return variantLabel;
    }

    int playerCount() {
        return playerCount;
    }

    String factionSet() {
        return factionSet;
    }

    int seatRotation() {
        return seatRotation;
    }

    String policyId() {
        return policyId;
    }

    UUID runCaseId() {
        return runCaseId;
    }

    UUID reproductionId() {
        return reproductionId;
    }

    String result() {
        return result;
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
}
