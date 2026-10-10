package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface EvidenceArtifactJpaRepository extends JpaRepository<EvidenceArtifactJpaEntity, String> {}
