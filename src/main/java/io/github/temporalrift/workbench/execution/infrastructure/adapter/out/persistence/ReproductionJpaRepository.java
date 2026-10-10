package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

interface ReproductionJpaRepository extends JpaRepository<ReproductionJpaEntity, UUID> {

    Optional<ReproductionJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    /** The oldest matching row nobody else has locked; a lock timeout of -2 makes Hibernate skip locked rows. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    Optional<ReproductionJpaEntity> findFirstWithLockByStateAndLaneIdInOrderByCreatedAt(
            String state, Collection<String> laneIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReproductionJpaEntity> findWithLockByReproductionIdAndStateAndLeaseOwner(
            UUID reproductionId, String state, String leaseOwner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ReproductionJpaEntity> findAllWithLockByState(String state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ReproductionJpaEntity> findAllWithLockByStateAndLeaseOwner(String state, String leaseOwner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ReproductionJpaEntity> findAllWithLockByStateAndLeaseExpiresAtBefore(String state, Instant now);
}
