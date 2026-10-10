package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EvidenceSourceOffsetJpaRepository
        extends JpaRepository<EvidenceSourceOffsetJpaEntity, EvidenceSourceOffsetJpaEntity.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EvidenceSourceOffsetJpaEntity> findWithLockByScopeIdAndGameIdAndSourceAndPartitionNo(
            UUID scopeId, UUID gameId, String source, int partitionNo);

    List<EvidenceSourceOffsetJpaEntity> findAllByScopeIdAndGameId(UUID scopeId, UUID gameId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EvidenceSourceOffsetJpaEntity o where o.scopeId = :scopeId")
    int deleteAllByScopeId(@Param("scopeId") UUID scopeId);
}
