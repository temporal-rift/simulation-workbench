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

interface RunCaseJpaRepository extends JpaRepository<RunCaseJpaEntity, UUID> {

    Optional<RunCaseJpaEntity> findByRunIdAndCaseId(UUID runId, UUID caseId);

    List<RunCaseJpaEntity> findAllByRunIdOrderByOrdinal(UUID runId);

    Optional<RunCaseJpaEntity> findFirstByRunIdAndStateOrderByOrdinal(UUID runId, String state);

    long countByRunIdAndState(UUID runId, String state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RunCaseJpaEntity> findWithLockByCaseIdAndState(UUID caseId, String state);

    /** One statement however many cases a run has, since a run may hold up to 100,000 of them. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RunCaseJpaEntity c set c.state = 'CANCELLED' where c.runId = :runId and c.state = 'PENDING'")
    int cancelPendingCases(@Param("runId") UUID runId);
}
