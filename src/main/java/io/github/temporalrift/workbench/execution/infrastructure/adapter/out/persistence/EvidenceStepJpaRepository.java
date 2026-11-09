package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.temporalrift.workbench.shared.window.WindowKind;

interface EvidenceStepJpaRepository extends JpaRepository<EvidenceStepJpaEntity, EvidenceStepJpaEntity.Key> {

    Optional<EvidenceStepJpaEntity> findFirstByScopeIdAndGameIdOrderByStepDesc(UUID scopeId, UUID gameId);

    List<EvidenceStepJpaEntity> findAllByScopeIdAndGameIdOrderByStep(UUID scopeId, UUID gameId);

    List<EvidenceStepJpaEntity> findByScopeIdAndGameIdAndStepGreaterThanOrderByStep(
            UUID scopeId, UUID gameId, int afterStep, Limit limit);

    List<EvidenceStepJpaEntity> findByScopeIdAndGameIdAndSeatIndexAndStepGreaterThanOrderByStep(
            UUID scopeId, UUID gameId, int seatIndex, int afterStep, Limit limit);

    /** Settles the unacknowledged steps of a seat's window with a round in one statement, without reading them. */
    @Modifying
    @Query("""
            update EvidenceStepJpaEntity s set s.outcome = :outcome, s.outcomeCode = :code
            where s.scopeId = :scopeId and s.gameId = :gameId and s.seatIndex = :seatIndex
              and s.windowKind = :windowKind and s.era = :era and s.round = :round and s.outcome = :unacknowledged
            """)
    int resolveInRound(
            @Param("scopeId") UUID scopeId,
            @Param("gameId") UUID gameId,
            @Param("seatIndex") int seatIndex,
            @Param("windowKind") WindowKind windowKind,
            @Param("era") int era,
            @Param("round") int round,
            @Param("unacknowledged") String unacknowledged,
            @Param("outcome") String outcome,
            @Param("code") String code);

    /** The same for a window that has no round. */
    @Modifying
    @Query("""
            update EvidenceStepJpaEntity s set s.outcome = :outcome, s.outcomeCode = :code
            where s.scopeId = :scopeId and s.gameId = :gameId and s.seatIndex = :seatIndex
              and s.windowKind = :windowKind and s.era = :era and s.round is null and s.outcome = :unacknowledged
            """)
    int resolveWithoutRound(
            @Param("scopeId") UUID scopeId,
            @Param("gameId") UUID gameId,
            @Param("seatIndex") int seatIndex,
            @Param("windowKind") WindowKind windowKind,
            @Param("era") int era,
            @Param("unacknowledged") String unacknowledged,
            @Param("outcome") String outcome,
            @Param("code") String code);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EvidenceStepJpaEntity s where s.scopeId = :scopeId")
    int deleteAllByScopeId(@Param("scopeId") UUID scopeId);
}
