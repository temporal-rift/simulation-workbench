package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.AssignedIdJpaEntity;

@Entity
@Table(name = "analysis_comparison")
class AnalysisComparisonJpaEntity extends AssignedIdJpaEntity<UUID> {

    @Id
    @Column(name = "comparison_id", nullable = false)
    private UUID comparisonId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "baseline_run_id", nullable = false)
    private UUID baselineRunId;

    @Column(name = "baseline_variant", nullable = false)
    private String baselineVariant;

    @Column(name = "candidate_run_id", nullable = false)
    private UUID candidateRunId;

    @Column(name = "candidate_variant", nullable = false)
    private String candidateVariant;

    @Column(name = "declared_differences", nullable = false, columnDefinition = "TEXT")
    private String declaredDifferences;

    @Column(name = "analysis_version", nullable = false, length = 32)
    private String analysisVersion;

    @Column(name = "analysis_seed", nullable = false, length = 20)
    private String analysisSeed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisComparisonJpaEntity() {}

    @SuppressWarnings("java:S107")
    AnalysisComparisonJpaEntity(
            UUID comparisonId,
            UUID idempotencyKey,
            String requestHash,
            UUID baselineRunId,
            String baselineVariant,
            UUID candidateRunId,
            String candidateVariant,
            String declaredDifferences,
            String analysisVersion,
            String analysisSeed,
            Instant createdAt) {
        this.comparisonId = comparisonId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.baselineRunId = baselineRunId;
        this.baselineVariant = baselineVariant;
        this.candidateRunId = candidateRunId;
        this.candidateVariant = candidateVariant;
        this.declaredDifferences = declaredDifferences;
        this.analysisVersion = analysisVersion;
        this.analysisSeed = analysisSeed;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return comparisonId;
    }

    UUID comparisonId() {
        return comparisonId;
    }

    String requestHash() {
        return requestHash;
    }

    UUID baselineRunId() {
        return baselineRunId;
    }

    String baselineVariant() {
        return baselineVariant;
    }

    UUID candidateRunId() {
        return candidateRunId;
    }

    String candidateVariant() {
        return candidateVariant;
    }

    String declaredDifferences() {
        return declaredDifferences;
    }

    String analysisVersion() {
        return analysisVersion;
    }

    String analysisSeed() {
        return analysisSeed;
    }

    Instant createdAt() {
        return createdAt;
    }
}
