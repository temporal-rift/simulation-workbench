package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    @Modifying
    @Query(value = """
                    INSERT INTO idempotency_key (idempotency_key, request_hash, experiment_id, created_at)
                    VALUES (:key, :requestHash, :experimentId, :createdAt)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("key") UUID key,
            @Param("requestHash") String requestHash,
            @Param("experimentId") UUID experimentId,
            @Param("createdAt") Instant createdAt);
}
