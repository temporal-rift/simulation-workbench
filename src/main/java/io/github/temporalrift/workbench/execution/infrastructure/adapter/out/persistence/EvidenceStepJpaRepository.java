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

import io.github.temporalrift.workbench.shared.window.WindowKind;

interface EvidenceStepJpaRepository extends JpaRepository<EvidenceStepJpaEntity, EvidenceStepJpaEntity.Key> {

    Optional<EvidenceStepJpaEntity> findFirstByScopeIdAndGameIdOrderByStepDesc(UUID scopeId, UUID gameId);

    List<EvidenceStepJpaEntity> findAllByScopeIdAndGameIdOrderByStep(UUID scopeId, UUID gameId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<EvidenceStepJpaEntity> findAllWithLockByScopeIdAndGameIdAndSeatIndexAndWindowKindAndEraAndRoundAndOutcome(
            UUID scopeId, UUID gameId, int seatIndex, WindowKind windowKind, int era, Integer round, String outcome);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EvidenceStepJpaEntity s where s.scopeId = :scopeId")
    int deleteAllByScopeId(@Param("scopeId") UUID scopeId);
}
