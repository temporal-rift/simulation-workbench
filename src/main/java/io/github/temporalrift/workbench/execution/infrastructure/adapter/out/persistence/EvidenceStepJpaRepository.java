package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EvidenceStepJpaRepository extends JpaRepository<EvidenceStepJpaEntity, EvidenceStepJpaEntity.Key> {

    /** The last recorded step number, read without loading the row and its observation text. */
    @Query("select max(s.step) from EvidenceStepJpaEntity s where s.scopeId = :scopeId and s.gameId = :gameId")
    Optional<Integer> lastStep(@Param("scopeId") UUID scopeId, @Param("gameId") UUID gameId);

    List<EvidenceStepJpaEntity> findAllByScopeIdAndGameIdOrderByStep(UUID scopeId, UUID gameId);

    List<EvidenceStepJpaEntity> findByScopeIdAndGameIdAndStepGreaterThanOrderByStep(
            UUID scopeId, UUID gameId, int afterStep, Limit limit);

    List<EvidenceStepJpaEntity> findByScopeIdAndGameIdAndSeatIndexAndStepGreaterThanOrderByStep(
            UUID scopeId, UUID gameId, int seatIndex, int afterStep, Limit limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EvidenceStepJpaEntity s where s.scopeId = :scopeId")
    int deleteAllByScopeId(@Param("scopeId") UUID scopeId);
}
