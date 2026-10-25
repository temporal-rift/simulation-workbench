package io.github.temporalrift.workbench.experiment.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.ExperimentVariant;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.ExperimentNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

class ExperimentCatalogQueryHandlerTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void anUnknownExperimentHasNoBoundsAndNoCases() {
        var catalog = catalogOf(null);

        assertThat(catalog.bounds(ID)).isEmpty();
        assertThat(catalog.snapshot(ID)).isEmpty();
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
    void boundsCarryEveryVariantWithItsEffectiveDigests() {
        var manifest = (ObjectNode) ExperimentManifests.valid("42", "a".repeat(64), "b".repeat(64));
        manifest.withArray("variants").get(1).asObject().put("effectiveRulesDigest", "f".repeat(64));
        var catalog = catalogOf(manifest);

        var variants = catalog.bounds(ID).orElseThrow().variants();

        assertThat(variants)
                .containsExactly(
                        new ExperimentVariant("baseline", "a".repeat(64), "b".repeat(64)),
                        new ExperimentVariant("candidate", "f".repeat(64), "b".repeat(64)));
    }

    @Test
    void boundsExposeTheTypedGameplayDefinition() {
        var manifest = ExperimentManifests.valid();

        var frozen = catalogOf(manifest).bounds(ID).orElseThrow().manifest();

        assertThat(frozen.seatRotationMode()).isEqualTo("CYCLIC");
        assertThat(frozen.timingMode()).isEqualTo("LOGICAL");
        assertThat(frozen.maxRejectedCandidatesPerWindow()).isEqualTo(10);
        assertThat(frozen.seeds()).hasSize(manifest.get("seeds").size());
        assertThat(frozen.policies()).hasSize(manifest.get("policies").size());
        assertThat(frozen.factionSets()).hasSize(manifest.get("factionSets").size());
        assertThat(frozen.variants()).hasSize(2);
    }

    @Test
    void casesAreTheMatrixInItsStableOrderWithTheirSeats() {
        var manifest = ExperimentManifests.valid();
        var catalog = catalogOf(manifest);

        var cases = catalog.snapshot(ID).orElseThrow().cases();

        var expected = MatrixPreviewService.preview(manifest, ManifestDigest.sha256Hex(manifest));
        assertThat(cases).hasSize(220).hasSameSizeAs(expected);
        assertThat(cases)
                .extracting(c -> c.caseKey())
                .isEqualTo(expected.stream().map(c -> c.caseKey()).toList());
        assertThat(cases).extracting(c -> c.caseKey()).doesNotHaveDuplicates();
        assertThat(cases.getFirst().seats()).hasSize(cases.getFirst().playerCount());
        assertThat(cases.getFirst().seats().getFirst().policyId()).isNotBlank();
    }

    @Test
    void aSnapshotCarriesTheBoundsAndTheCasesFromOneStoredRead() {
        var manifest = ExperimentManifests.valid();
        var reads = new AtomicInteger();
        var catalog = catalogOf(manifest, reads);

        var snapshot = catalog.snapshot(ID).orElseThrow();

        assertThat(reads).hasValue(1);
        assertThat(snapshot.bounds()).isEqualTo(catalog.bounds(ID).orElseThrow());
        assertThat(snapshot.cases()).hasSize(220);
    }

    @Test
    void aVariantsRulesAreThoseTheRulesUseCaseRebuildsForIt() {
        var rules = VariantRulesFixtures.baseline();
        var catalog = new ExperimentCatalogQueryHandler(mock(ExperimentRepository.class), (id, label) -> {
            assertThat(id).isEqualTo(ID);
            assertThat(label).isEqualTo("baseline");
            return rules;
        });

        assertThat(catalog.variantRules(ID, "baseline")).contains(rules);
    }

    @Test
    void aVariantWhoseRulesAreNotRetainedHasNoRules() {
        var catalog = new ExperimentCatalogQueryHandler(mock(ExperimentRepository.class), (id, label) -> {
            throw new ExperimentNotFoundException(id, label);
        });

        assertThat(catalog.variantRules(ID, "candidate")).isEmpty();
    }

    private ExperimentCatalogQueryHandler catalogOf(JsonNode manifest) {
        return catalogOf(manifest, new AtomicInteger());
    }

    private ExperimentCatalogQueryHandler catalogOf(JsonNode manifest, AtomicInteger reads) {
        return new ExperimentCatalogQueryHandler(
                new ExperimentRepository() {
                    @Override
                    public void save(
                            UUID id,
                            String digest,
                            String json,
                            String name,
                            Instant createdAt,
                            Map<String, String> baselineDocuments) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<String> findBaselineDocument(UUID experimentId, String rulesArtifactDigest) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<StoredExperiment> findById(UUID experimentId) {
                        reads.incrementAndGet();
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
                (_, _) -> {
                    throw new UnsupportedOperationException();
                });
    }
}
