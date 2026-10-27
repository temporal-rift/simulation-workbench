package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface FidelityCheckJpaRepository extends JpaRepository<FidelityCheckJpaEntity, UUID> {

    Optional<FidelityCheckJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    Optional<FidelityCheckJpaEntity> findFirstByStateInOrderByCreatedAtAsc(Collection<String> states);

    Page<FidelityCheckJpaEntity> findAllByOrderByCreatedAtDescCheckIdDesc(Pageable page);

    Page<FidelityCheckJpaEntity> findByStateOrderByCreatedAtDescCheckIdDesc(String state, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FidelityCheckJpaEntity> findWithLockByCheckIdAndState(UUID checkId, String state);

    Optional<FidelityCheckJpaEntity>
            findFirstByStateAndGameRevisionAndTimelineRevisionAndReadRevisionOrderByFinishedAtDesc(
                    String state, String gameRevision, String timelineRevision, String readRevision);
}
