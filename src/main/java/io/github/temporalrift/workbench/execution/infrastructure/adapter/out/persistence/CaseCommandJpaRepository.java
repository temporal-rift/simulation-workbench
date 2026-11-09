package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
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

interface CaseCommandJpaRepository extends JpaRepository<CaseCommandJpaEntity, CaseCommandJpaEntity.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CaseCommandJpaEntity> findWithLockByCaseIdAndSeatIndexAndWindowKindAndEraAndRound(
            UUID caseId, int seatIndex, WindowKind windowKind, int era, int round);

    /** Settles the slot in one statement, without reading its row first; a slot that does not exist is left alone. */
    @Modifying
    @Query("""
            update CaseCommandJpaEntity c
            set c.status = :status, c.resolutionKind = :kind, c.resolutionCode = :code, c.updatedAt = :now
            where c.caseId = :caseId and c.seatIndex = :seatIndex and c.windowKind = :windowKind
              and c.era = :era and c.round = :round
            """)
    int resolve(
            @Param("caseId") UUID caseId,
            @Param("seatIndex") int seatIndex,
            @Param("windowKind") WindowKind windowKind,
            @Param("era") int era,
            @Param("round") int round,
            @Param("status") String status,
            @Param("kind") String kind,
            @Param("code") String code,
            @Param("now") Instant now);

    List<CaseCommandJpaEntity> findAllByCaseIdAndStatus(UUID caseId, String status);

    void deleteAllByCaseId(UUID caseId);
}
