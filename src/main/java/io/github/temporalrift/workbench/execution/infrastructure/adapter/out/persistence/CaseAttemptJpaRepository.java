package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** The failed attempts of the run's failed cases, each case's attempts in ordinal order. */
    @Query("""
            select a from CaseAttemptJpaEntity a
            where a.state = 'FAILED'
              and a.caseId in (select c.caseId from RunCaseJpaEntity c where c.runId = :runId and c.state = 'FAILED')
            order by a.caseId, a.ordinal
            """)
    List<CaseAttemptJpaEntity> findFailedAttemptsOfFailedCases(@Param("runId") UUID runId);
}
