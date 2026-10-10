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
@Table(name = "evidence_event")
@IdClass(EvidenceEventJpaEntity.Key.class)
class EvidenceEventJpaEntity extends AssignedIdJpaEntity<EvidenceEventJpaEntity.Key> {

    @Id
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Id
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Id
    @Column(name = "source", nullable = false, length = 249)
    private String source;

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    @Column(name = "aggregate_type", length = 100)
    private String aggregateType;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "version")
    private Integer version;

    @Column(name = "partition_no", nullable = false)
    private int partitionNo;

    @Column(name = "offset_no", nullable = false)
    private long offsetNo;

    @Column(name = "payload_artifact", nullable = false, length = 64)
    private String payloadArtifact;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected EvidenceEventJpaEntity() {}

    @SuppressWarnings("java:S107")
    EvidenceEventJpaEntity(
            UUID scopeId,
            UUID gameId,
            String source,
            UUID eventId,
            UUID attemptId,
            String eventType,
            UUID aggregateId,
            String aggregateType,
            Instant occurredAt,
            Integer version,
            int partitionNo,
            long offsetNo,
            String payloadArtifact,
            Instant observedAt) {
        this.scopeId = scopeId;
        this.gameId = gameId;
        this.source = source;
        this.eventId = eventId;
        this.attemptId = attemptId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.occurredAt = occurredAt;
        this.version = version;
        this.partitionNo = partitionNo;
        this.offsetNo = offsetNo;
        this.payloadArtifact = payloadArtifact;
        this.observedAt = observedAt;
    }

    @Override
    public Key getId() {
        return new Key(scopeId, gameId, source, eventId);
    }

    String source() {
        return source;
    }

    UUID eventId() {
        return eventId;
    }

    String eventType() {
        return eventType;
    }

    UUID aggregateId() {
        return aggregateId;
    }

    String aggregateType() {
        return aggregateType;
    }

    UUID gameId() {
        return gameId;
    }

    Instant occurredAt() {
        return occurredAt;
    }

    Integer version() {
        return version;
    }

    int partitionNo() {
        return partitionNo;
    }

    long offsetNo() {
        return offsetNo;
    }

    String payloadArtifact() {
        return payloadArtifact;
    }

    record Key(UUID scopeId, UUID gameId, String source, UUID eventId) implements Serializable {}
}
