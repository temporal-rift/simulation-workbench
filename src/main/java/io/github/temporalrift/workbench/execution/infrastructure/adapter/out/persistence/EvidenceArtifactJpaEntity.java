package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "evidence_artifact")
class EvidenceArtifactJpaEntity extends AssignedIdJpaEntity<String> {

    @Id
    @Column(name = "digest", nullable = false, length = 64)
    private String digest;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EvidenceArtifactJpaEntity() {}

    EvidenceArtifactJpaEntity(String digest, String mediaType, byte[] content, Instant createdAt) {
        this.digest = digest;
        this.mediaType = mediaType;
        this.content = content;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return digest;
    }

    byte[] content() {
        return content;
    }
}
