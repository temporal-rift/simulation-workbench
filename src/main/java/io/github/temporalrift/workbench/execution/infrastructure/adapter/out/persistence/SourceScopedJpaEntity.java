package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

/** The part of the key shared by everything retained about one source of one game in one evidence scope. */
@MappedSuperclass
abstract class SourceScopedJpaEntity<K> extends AssignedIdJpaEntity<K> {

    @Id
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Id
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Id
    @Column(name = "source", nullable = false, length = 249)
    private String source;

    protected SourceScopedJpaEntity() {}

    protected SourceScopedJpaEntity(UUID scopeId, UUID gameId, String source) {
        this.scopeId = scopeId;
        this.gameId = gameId;
        this.source = source;
    }

    UUID scopeId() {
        return scopeId;
    }

    UUID gameId() {
        return gameId;
    }

    String source() {
        return source;
    }
}
