package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "fidelity_check")
class FidelityCheckJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "check_id", nullable = false)
    private UUID checkId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "seed", nullable = false, length = 20)
    private String seed;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "lane_id", nullable = false, length = 64)
    private String laneId;

    @Column(name = "simulator_json", nullable = false, columnDefinition = "TEXT")
    private String simulatorJson;

    @Column(name = "lane_json", nullable = false, columnDefinition = "TEXT")
    private String laneJson;

    @Column(name = "variants_json", nullable = false, columnDefinition = "TEXT")
    private String variantsJson;

    @Column(name = "game_revision", nullable = false, length = 64)
    private String gameRevision;

    @Column(name = "timeline_revision", nullable = false, length = 64)
    private String timelineRevision;

    @Column(name = "read_revision", nullable = false, length = 64)
    private String readRevision;

    @Column(name = "experiment_id")
    private UUID experimentId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected FidelityCheckJpaEntity() {}

    /** What a check is when it is accepted. */
    record Accepted(
            UUID checkId,
            UUID idempotencyKey,
            String requestHash,
            String seed,
            String laneId,
            String simulatorJson,
            String laneJson,
            String variantsJson,
            String gameRevision,
            String timelineRevision,
            String readRevision,
            Instant createdAt) {}

    FidelityCheckJpaEntity(Accepted accepted) {
        this.checkId = accepted.checkId();
        this.idempotencyKey = accepted.idempotencyKey();
        this.requestHash = accepted.requestHash();
        this.seed = accepted.seed();
        this.state = "QUEUED";
        this.laneId = accepted.laneId();
        this.simulatorJson = accepted.simulatorJson();
        this.laneJson = accepted.laneJson();
        this.variantsJson = accepted.variantsJson();
        this.gameRevision = accepted.gameRevision();
        this.timelineRevision = accepted.timelineRevision();
        this.readRevision = accepted.readRevision();
        this.createdAt = accepted.createdAt();
    }

    @Override
    public UUID getId() {
        return checkId;
    }

    /** What a check has become since it was accepted. */
    record Progress(
            String state,
            UUID experimentId,
            UUID runId,
            String failureCode,
            String failureMessage,
            Instant startedAt,
            Instant finishedAt) {}

    void advance(Progress progress) {
        this.state = progress.state();
        this.experimentId = progress.experimentId();
        this.runId = progress.runId();
        this.failureCode = progress.failureCode();
        this.failureMessage = progress.failureMessage();
        this.startedAt = progress.startedAt();
        this.finishedAt = progress.finishedAt();
    }

    UUID checkId() {
        return checkId;
    }

    String requestHash() {
        return requestHash;
    }

    String seed() {
        return seed;
    }

    String state() {
        return state;
    }

    String laneId() {
        return laneId;
    }

    String simulatorJson() {
        return simulatorJson;
    }

    String laneJson() {
        return laneJson;
    }

    String variantsJson() {
        return variantsJson;
    }

    UUID experimentId() {
        return experimentId;
    }

    UUID runId() {
        return runId;
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

    Instant startedAt() {
        return startedAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }
}
