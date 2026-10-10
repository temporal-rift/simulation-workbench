package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface RunJpaRepository extends JpaRepository<RunJpaEntity, UUID> {

    List<RunJpaEntity> findAllByStateOrderByCreatedAt(String state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RunJpaEntity> findWithLockByRunIdAndState(UUID runId, String state);
}
