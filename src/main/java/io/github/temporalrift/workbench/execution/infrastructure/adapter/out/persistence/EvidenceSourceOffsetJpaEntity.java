package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "evidence_source_offset")
@IdClass(EvidenceSourceOffsetJpaEntity.Key.class)
class EvidenceSourceOffsetJpaEntity extends SourceScopedJpaEntity<EvidenceSourceOffsetJpaEntity.Key> {

    @Id
    @Column(name = "partition_no", nullable = false)
    private int partitionNo;

    @Column(name = "next_offset", nullable = false)
    private long nextOffset;

    protected EvidenceSourceOffsetJpaEntity() {}

    @Override
    public Key getId() {
        return new Key(scopeId(), gameId(), source(), partitionNo);
    }

    int partitionNo() {
        return partitionNo;
    }

    long nextOffset() {
        return nextOffset;
    }

    record Key(UUID scopeId, UUID gameId, String source, int partitionNo) implements Serializable {}
}
