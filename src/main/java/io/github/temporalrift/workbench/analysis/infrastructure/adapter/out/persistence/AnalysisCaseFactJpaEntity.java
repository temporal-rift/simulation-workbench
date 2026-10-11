package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "analysis_case_fact")
@IdClass(AnalysisCaseFactJpaEntity.Key.class)
class AnalysisCaseFactJpaEntity extends AssignedIdJpaEntity<AnalysisCaseFactJpaEntity.Key> {

    @Id
    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Id
    @Column(name = "analysis_version", nullable = false, length = 32)
    private String analysisVersion;

    @Column(name = "facts", nullable = false, columnDefinition = "TEXT")
    private String facts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisCaseFactJpaEntity() {}

    AnalysisCaseFactJpaEntity(UUID caseId, String analysisVersion, String facts, Instant createdAt) {
        this.caseId = caseId;
        this.analysisVersion = analysisVersion;
        this.facts = facts;
        this.createdAt = createdAt;
    }

    @Override
    public Key getId() {
        return new Key(caseId, analysisVersion);
    }

    String facts() {
        return facts;
    }

    record Key(UUID caseId, String analysisVersion) implements Serializable {}
}
