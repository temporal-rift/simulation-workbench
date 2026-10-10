package io.github.temporalrift.workbench.experiment.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

class ExperimentCatalogQueryHandlerTest {

    private static final UUID ID = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anUnknownExperimentHasNoBoundsAndNoCases() {
        var catalog = catalogOf(null);

        assertThat(catalog.bounds(ID)).isEmpty();
        assertThat(catalog.cases(ID)).isEmpty();
    }

    @Test
    void boundsAreTheFrozenManifestsExecutionInputs() {
        var manifest = ExperimentManifests.valid();
        var catalog = catalogOf(manifest);

        var bounds = catalog.bounds(ID).orElseThrow();

        assertThat(bounds.manifestDigest()).isEqualTo(ManifestDigest.sha256Hex(manifest));
        assertThat(bounds.concurrency()).isEqualTo(4);
        assertThat(bounds.caseWallTimeoutSeconds()).isEqualTo(300);
        assertThat(bounds.maxRejectedCandidatesPerWindow()).isEqualTo(10);
    }

    @Test
    void casesAreTheMatrixInItsStableOrderWithTheirSeats() {
        var manifest = ExperimentManifests.valid();
        var catalog = catalogOf(manifest);

        var cases = catalog.cases(ID).orElseThrow();

        var expected = MatrixPreviewService.preview(manifest, ManifestDigest.sha256Hex(manifest));
        assertThat(cases).hasSize(220).hasSameSizeAs(expected);
        assertThat(cases)
                .extracting(c -> c.caseKey())
                .isEqualTo(expected.stream().map(c -> c.caseKey()).toList());
        assertThat(cases).extracting(c -> c.caseKey()).doesNotHaveDuplicates();
        assertThat(cases.getFirst().seats()).hasSize(cases.getFirst().playerCount());
        assertThat(cases.getFirst().seats().getFirst().policyId()).isNotBlank();
    }

    private ExperimentCatalogQueryHandler catalogOf(JsonNode manifest) {
        return new ExperimentCatalogQueryHandler(
                new ExperimentRepository() {
                    @Override
                    public void save(UUID id, String digest, String json, String name, Instant createdAt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<StoredExperiment> findById(UUID experimentId) {
                        if (manifest == null || !experimentId.equals(ID)) {
                            return Optional.empty();
                        }
                        return Optional.of(new StoredExperiment(
                                ID, ManifestDigest.sha256Hex(manifest), manifest.toString(), "x", Instant.now()));
                    }

                    @Override
                    public List<StoredExperiment> findNewest(int limit, int offset) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public long count() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void delete(UUID experimentId) {
                        throw new UnsupportedOperationException();
                    }
                },
                mapper);
    }
}
