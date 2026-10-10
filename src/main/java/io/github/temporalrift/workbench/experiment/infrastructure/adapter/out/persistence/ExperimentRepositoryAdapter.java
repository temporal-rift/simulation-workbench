package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.OffsetLimit;

/** JPA-backed frozen experiment storage. Rows are inserted once and never updated. */
public class ExperimentRepositoryAdapter implements ExperimentRepository {

    private final ExperimentJpaRepository repository;

    public ExperimentRepositoryAdapter(ExperimentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(UUID experimentId, String manifestDigest, String manifestJson, String name, Instant createdAt) {
        repository.save(new ExperimentEntity(experimentId, manifestDigest, manifestJson, name, createdAt));
    }

    @Override
    public Optional<StoredExperiment> findById(UUID experimentId) {
        return repository.findById(experimentId).map(ExperimentRepositoryAdapter::stored);
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
