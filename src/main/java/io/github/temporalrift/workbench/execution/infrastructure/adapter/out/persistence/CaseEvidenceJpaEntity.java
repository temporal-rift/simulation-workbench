package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "case_evidence")
class CaseEvidenceJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "manifest_digest", nullable = false, length = 64)
    private String manifestDigest;

    @Column(name = "manifest_artifact", nullable = false, length = 64)
    private String manifestArtifact;

    @Column(name = "transcript_artifact", length = 64)
    private String transcriptArtifact;

    @Column(name = "result_digest", length = 64)
    private String resultDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    protected CaseEvidenceJpaEntity() {}

    CaseEvidenceJpaEntity(UUID scopeId, String manifestDigest, String manifestArtifact, Instant createdAt) {
        this.scopeId = scopeId;
        this.manifestDigest = manifestDigest;
        this.manifestArtifact = manifestArtifact;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return scopeId;
    }

    void seal(String transcriptArtifact, String resultDigest, Instant sealedAt) {
        this.transcriptArtifact = transcriptArtifact;
        this.resultDigest = resultDigest;
        this.sealedAt = sealedAt;
    }

    String manifestDigest() {
        return manifestDigest;
    }

    String manifestArtifact() {
        return manifestArtifact;
    }

    String transcriptArtifact() {
        return transcriptArtifact;
    }

    String resultDigest() {
        return resultDigest;
    }

    Instant sealedAt() {
        return sealedAt;
    }
}
