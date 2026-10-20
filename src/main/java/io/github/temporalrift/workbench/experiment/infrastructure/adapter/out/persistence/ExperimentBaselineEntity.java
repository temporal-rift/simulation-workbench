package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

/** A baseline rules document retained for a frozen experiment, under the digest of the rules artifact naming it. */
@Entity
@Table(name = "experiment_baseline")
@IdClass(ExperimentBaselineEntity.Key.class)
class ExperimentBaselineEntity extends AssignedIdJpaEntity<ExperimentBaselineEntity.Key> {

    @Id
    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Id
    @Column(name = "rules_artifact_digest", nullable = false, length = 64)
    private String rulesArtifactDigest;

    @Column(name = "document", nullable = false, columnDefinition = "TEXT")
    private String document;

    protected ExperimentBaselineEntity() {}

    ExperimentBaselineEntity(UUID experimentId, String rulesArtifactDigest, String document) {
        this.experimentId = experimentId;
        this.rulesArtifactDigest = rulesArtifactDigest;
        this.document = document;
    }

    @Override
    public Key getId() {
        return new Key(experimentId, rulesArtifactDigest);
    }

    String document() {
        return document;
    }

    record Key(UUID experimentId, String rulesArtifactDigest) implements Serializable {}
}
