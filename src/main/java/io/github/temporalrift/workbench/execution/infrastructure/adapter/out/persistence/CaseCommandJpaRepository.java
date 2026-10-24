package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import io.github.temporalrift.workbench.shared.window.WindowKind;

interface CaseCommandJpaRepository extends JpaRepository<CaseCommandJpaEntity, CaseCommandJpaEntity.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CaseCommandJpaEntity> findWithLockByCaseIdAndSeatIndexAndWindowKindAndEraAndRound(
            UUID caseId, int seatIndex, WindowKind windowKind, int era, int round);

    List<CaseCommandJpaEntity> findAllByCaseIdAndStatus(UUID caseId, String status);

    void deleteAllByCaseId(UUID caseId);
}
