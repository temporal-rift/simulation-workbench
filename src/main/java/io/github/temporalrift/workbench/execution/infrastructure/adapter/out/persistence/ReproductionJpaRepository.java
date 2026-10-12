package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface ReproductionJpaRepository extends JpaRepository<ReproductionJpaEntity, UUID> {

    Optional<ReproductionJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

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
