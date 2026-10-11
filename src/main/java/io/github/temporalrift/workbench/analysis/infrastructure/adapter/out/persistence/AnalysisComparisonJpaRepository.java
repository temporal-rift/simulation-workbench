package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnalysisComparisonJpaRepository extends JpaRepository<AnalysisComparisonJpaEntity, UUID> {

    Optional<AnalysisComparisonJpaEntity> findByIdempotencyKey(UUID idempotencyKey);
}
