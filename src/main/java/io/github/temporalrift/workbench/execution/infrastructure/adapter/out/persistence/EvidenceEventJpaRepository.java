package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EvidenceEventJpaRepository extends JpaRepository<EvidenceEventJpaEntity, EvidenceEventJpaEntity.Key> {

    List<EvidenceEventJpaEntity> findAllByScopeIdAndGameId(UUID scopeId, UUID gameId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EvidenceEventJpaEntity e where e.scopeId = :scopeId")
    int deleteAllByScopeId(@Param("scopeId") UUID scopeId);
}
