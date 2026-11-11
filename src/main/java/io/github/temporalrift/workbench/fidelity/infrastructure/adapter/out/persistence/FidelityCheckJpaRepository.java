package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FidelityCheckJpaRepository extends JpaRepository<FidelityCheckJpaEntity, UUID> {

    Optional<FidelityCheckJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    Optional<FidelityCheckJpaEntity> findFirstByStateInOrderByCreatedAtAsc(Collection<String> states);

    Page<FidelityCheckJpaEntity> findAllByOrderByCreatedAtDescCheckIdDesc(Pageable page);

    Page<FidelityCheckJpaEntity> findByStateOrderByCreatedAtDescCheckIdDesc(String state, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FidelityCheckJpaEntity> findWithLockByCheckIdAndState(UUID checkId, String state);

    /** The checks in {@code state} that ran with exactly these revisions on this parameter space, newest first. */
    @Query("""
            select c from FidelityCheckJpaEntity c
            where c.state = :state
              and c.gameRevision = :gameRevision
              and c.timelineRevision = :timelineRevision
              and c.readRevision = :readRevision
              and c.parameterSpaceVersion = :parameterSpaceVersion
            order by c.finishedAt desc
            """)
    List<FidelityCheckJpaEntity> newest(
            @Param("state") String state,
            @Param("gameRevision") String gameRevision,
            @Param("timelineRevision") String timelineRevision,
            @Param("readRevision") String readRevision,
            @Param("parameterSpaceVersion") int parameterSpaceVersion,
            Pageable page);
}
