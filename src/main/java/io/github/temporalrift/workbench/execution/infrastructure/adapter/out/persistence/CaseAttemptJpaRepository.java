package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface CaseAttemptJpaRepository extends JpaRepository<CaseAttemptJpaEntity, UUID> {

    List<CaseAttemptJpaEntity> findAllByCaseIdOrderByOrdinal(UUID caseId);

    Optional<CaseAttemptJpaEntity> findFirstByCaseIdOrderByOrdinalDesc(UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CaseAttemptJpaEntity> findWithLockByAttemptIdAndState(UUID attemptId, String state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CaseAttemptJpaEntity> findWithLockByAttemptIdAndLeaseOwnerAndState(
            UUID attemptId, String leaseOwner, String state);

    List<CaseAttemptJpaEntity> findAllByState(String state);

    List<CaseAttemptJpaEntity> findAllByStateAndLeaseOwner(String state, String leaseOwner);

    List<CaseAttemptJpaEntity> findAllByStateAndLeaseExpiresAtBefore(String state, Instant now);
}
