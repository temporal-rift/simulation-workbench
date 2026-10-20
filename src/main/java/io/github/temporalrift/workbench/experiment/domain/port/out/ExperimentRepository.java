package io.github.temporalrift.workbench.experiment.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persists frozen experiments exactly once; stored rows are never updated or deleted. */
public interface ExperimentRepository {

    /**
     * Stores the experiment with the baseline rules documents its variants use, together or not at all.
     *
     * @param baselineDocuments the documents' text by the digest of the rules artifact that names them
     */
    void save(
            UUID experimentId,
            String manifestDigest,
            String manifestJson,
            String name,
            Instant createdAt,
            Map<String, String> baselineDocuments);

    Optional<StoredExperiment> findById(UUID experimentId);

    /** The baseline document retained for the rules artifact when the experiment was frozen. */
    Optional<String> findBaselineDocument(UUID experimentId, String rulesArtifactDigest);

    /** Experiments newest first; {@code offset} skips that many of them. */
    List<StoredExperiment> findNewest(int limit, int offset);

    long count();

    /** Removes an experiment row. Only used to compensate a lost idempotency race. */
    void delete(UUID experimentId);

    record StoredExperiment(
            UUID experimentId, String manifestDigest, String manifestJson, String name, Instant createdAt) {}
}
