package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.OffsetLimit;

/** JPA-backed frozen experiment storage. Rows are inserted once and never updated. */
public class ExperimentRepositoryAdapter implements ExperimentRepository {

    private final ExperimentJpaRepository repository;
    private final ExperimentBaselineJpaRepository baselines;

    public ExperimentRepositoryAdapter(ExperimentJpaRepository repository, ExperimentBaselineJpaRepository baselines) {
        this.repository = repository;
        this.baselines = baselines;
    }

    @Override
    @Transactional
    public void save(
            UUID experimentId,
            String manifestDigest,
            String manifestJson,
            String name,
            Instant createdAt,
            Map<String, String> baselineDocuments) {
        repository.saveAndFlush(new ExperimentEntity(experimentId, manifestDigest, manifestJson, name, createdAt));
        baselines.saveAll(baselineDocuments.entrySet().stream()
                .map(document -> new ExperimentBaselineEntity(experimentId, document.getKey(), document.getValue()))
                .toList());
    }

    @Override
    public Optional<StoredExperiment> findById(UUID experimentId) {
        return repository.findById(experimentId).map(ExperimentRepositoryAdapter::stored);
    }

    @Override
    public Optional<String> findBaselineDocument(UUID experimentId, String rulesArtifactDigest) {
        return baselines
                .findById(new ExperimentBaselineEntity.Key(experimentId, rulesArtifactDigest))
                .map(ExperimentBaselineEntity::document);
    }

    @Override
    public List<StoredExperiment> findNewest(int limit, int offset) {
        return repository.findAllByOrderByCreatedAtDescExperimentIdDesc(new OffsetLimit(offset, limit)).stream()
                .map(ExperimentRepositoryAdapter::stored)
                .toList();
    }

    @Override
    public long count() {
        return repository.count();
    }

    private static StoredExperiment stored(ExperimentEntity entity) {
        return new StoredExperiment(
                entity.experimentId(),
                entity.manifestDigest(),
                entity.manifestJson(),
                entity.name(),
                entity.createdAt());
    }

    @Override
    public void delete(UUID experimentId) {
        repository.deleteById(experimentId);
    }
}
