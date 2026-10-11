package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnalysisCaseFactJpaRepository
        extends JpaRepository<AnalysisCaseFactJpaEntity, AnalysisCaseFactJpaEntity.Key> {

    List<AnalysisCaseFactJpaEntity> findAllByCaseIdInAndAnalysisVersion(
            Collection<UUID> caseIds, String analysisVersion);
}
