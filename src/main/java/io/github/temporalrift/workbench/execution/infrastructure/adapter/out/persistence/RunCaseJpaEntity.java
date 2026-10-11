package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository.NewCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "run_case")
class RunCaseJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "case_key", nullable = false)
    private UUID caseKey;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "variant_label", nullable = false, length = 120)
    private String variantLabel;

    @Column(name = "seed", nullable = false, length = 20)
    private String seed;

    @Column(name = "player_count", nullable = false)
    private int playerCount;

    @Column(name = "seats_json", nullable = false, columnDefinition = "TEXT")
    private String seatsJson;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    protected RunCaseJpaEntity() {}

    RunCaseJpaEntity(UUID caseId, UUID runId, NewCase plan, String seatsJson) {
        this.caseId = caseId;
        this.runId = runId;
        this.caseKey = plan.caseKey();
        this.ordinal = plan.ordinal();
        this.variantLabel = plan.variantLabel();
        this.seed = plan.seed();
        this.playerCount = plan.playerCount();
        this.seatsJson = seatsJson;
        this.state = "PENDING";
    }

    @Override
    public UUID getId() {
        return caseId;
    }

    void settle(String state, String resultJson) {
        this.state = state;
        this.resultJson = resultJson;
    }

    UUID caseId() {
        return caseId;
    }

    UUID runId() {
        return runId;
    }

    UUID caseKey() {
        return caseKey;
    }

    int ordinal() {
        return ordinal;
    }

    String variantLabel() {
        return variantLabel;
    }

    String seed() {
        return seed;
    }

    int playerCount() {
        return playerCount;
    }

    String seatsJson() {
        return seatsJson;
    }

    String state() {
        return state;
    }

    String resultJson() {
        return resultJson;
    }
}
