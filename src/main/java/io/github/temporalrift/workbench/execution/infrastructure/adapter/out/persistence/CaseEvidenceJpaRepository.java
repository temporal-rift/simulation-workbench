package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaseEvidenceJpaRepository extends JpaRepository<CaseEvidenceJpaEntity, UUID> {

    Optional<CaseEvidenceJpaEntity> findByScopeIdAndSealedAtIsNotNull(UUID scopeId);
}
