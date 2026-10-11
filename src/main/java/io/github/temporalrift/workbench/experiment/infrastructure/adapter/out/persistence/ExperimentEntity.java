package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "experiment")
class ExperimentEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Column(name = "manifest_digest", nullable = false, length = 64)
    private String manifestDigest;

    @Column(name = "manifest_json", nullable = false, columnDefinition = "TEXT")
    private String manifestJson;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExperimentEntity() {}

    ExperimentEntity(UUID experimentId, String manifestDigest, String manifestJson, String name, Instant createdAt) {
        this.experimentId = experimentId;
        this.manifestDigest = manifestDigest;
        this.manifestJson = manifestJson;
        this.name = name;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return experimentId;
    }

    UUID experimentId() {
        return experimentId;
    }

    String manifestDigest() {
        return manifestDigest;
    }

    String manifestJson() {
        return manifestJson;
    }

    String name() {
        return name;
    }

    Instant createdAt() {
        return createdAt;
    }
}
