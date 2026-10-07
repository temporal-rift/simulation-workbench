package io.github.temporalrift.workbench.experiment.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persists frozen experiments exactly once; stored rows are never updated or deleted. */
public interface ExperimentRepository {

    void save(UUID experimentId, String manifestDigest, String manifestJson, String name, Instant createdAt);

    Optional<StoredExperiment> findById(UUID experimentId);

    record StoredExperiment(
            UUID experimentId, String manifestDigest, String manifestJson, String name, Instant createdAt) {}
}
