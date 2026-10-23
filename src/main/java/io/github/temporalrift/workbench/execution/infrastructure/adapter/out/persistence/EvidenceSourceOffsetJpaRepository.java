package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EvidenceSourceOffsetJpaRepository
        extends JpaRepository<EvidenceSourceOffsetJpaEntity, EvidenceSourceOffsetJpaEntity.Key> {

    List<EvidenceSourceOffsetJpaEntity> findAllByScopeIdAndGameId(UUID scopeId, UUID gameId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EvidenceSourceOffsetJpaEntity o where o.scopeId = :scopeId")
    int deleteAllByScopeId(@Param("scopeId") UUID scopeId);
}
