package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "evidence_source_offset")
@IdClass(EvidenceSourceOffsetJpaEntity.Key.class)
class EvidenceSourceOffsetJpaEntity extends AssignedIdJpaEntity<EvidenceSourceOffsetJpaEntity.Key> {

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
    @Column(name = "partition_no", nullable = false)
    private int partitionNo;

    @Column(name = "next_offset", nullable = false)
    private long nextOffset;

    protected EvidenceSourceOffsetJpaEntity() {}

    EvidenceSourceOffsetJpaEntity(UUID scopeId, UUID gameId, String source, int partitionNo, long nextOffset) {
        this.scopeId = scopeId;
        this.gameId = gameId;
        this.source = source;
        this.partitionNo = partitionNo;
        this.nextOffset = nextOffset;
    }

    @Override
    public Key getId() {
        return new Key(scopeId, gameId, source, partitionNo);
    }

    /** The offset never moves back. */
    void advanceTo(long offset) {
        this.nextOffset = Math.max(nextOffset, offset);
    }

    String source() {
        return source;
    }

    int partitionNo() {
        return partitionNo;
    }

    long nextOffset() {
        return nextOffset;
    }

    record Key(UUID scopeId, UUID gameId, String source, int partitionNo) implements Serializable {}
}
