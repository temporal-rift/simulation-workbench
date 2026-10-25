package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EvidenceEventJpaRepository extends JpaRepository<EvidenceEventJpaEntity, EvidenceEventJpaEntity.Key> {

    List<EvidenceEventJpaEntity> findAllByScopeIdAndGameId(UUID scopeId, UUID gameId);

    List<EvidenceEventJpaEntity> findAllByScopeIdAndGameIdAndEventType(UUID scopeId, UUID gameId, String eventType);

    /** Byte-order collation keeps the page order identical to the Java order of {@code source}. */
    @Query(value = """
                    select * from evidence_event
                    where scope_id = :scopeId and game_id = :gameId
                    order by source collate "C", partition_no, offset_no
                    limit :limit offset :offset
                    """, nativeQuery = true)
    List<EvidenceEventJpaEntity> findPage(
            @Param("scopeId") UUID scopeId,
            @Param("gameId") UUID gameId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EvidenceEventJpaEntity e where e.scopeId = :scopeId")
    int deleteAllByScopeId(@Param("scopeId") UUID scopeId);
}
