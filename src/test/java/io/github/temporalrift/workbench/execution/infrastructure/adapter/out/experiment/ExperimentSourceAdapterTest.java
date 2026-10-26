package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.Provenances;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.StoredManifests;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

class ExperimentSourceAdapterTest {

    private static final UUID EXPERIMENT = new UUID(1, 1);

    private final ExperimentCatalog catalog = mock(ExperimentCatalog.class);
    private final ExperimentSourceAdapter source = new ExperimentSourceAdapter(catalog);

    @Test
    void eachVariantNeedsTheLaneItsContentServicesAndContractsDescribe() {
        var manifest = ExperimentManifests.threePlayerTwoVariants();
        when(catalog.bounds(EXPERIMENT)).thenReturn(Optional.of(bounds(manifest)));

        var variants = source.plan(EXPERIMENT).orElseThrow().variants();

        assertThat(variants)
                .extracting(ExperimentSource.Variant::label, ExperimentSource.Variant::fit)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(
                                "baseline", Provenances.fitOf(new BundleDigests("a".repeat(64), "b".repeat(64)))),
                        org.assertj.core.api.Assertions.tuple(
                                "candidate", Provenances.fitOf(new BundleDigests("f".repeat(64), "b".repeat(64)))));
        // The two variants differ in rules only, so one lane fits both.
        assertThat(variants.get(0).fit()).isEqualTo(variants.get(1).fit());
    }

    @Test
    void aVariantOfAnotherContentFitsOtherLanes() {
        var manifest = ExperimentManifests.threePlayerTwoVariants().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) manifest.withArray("variants").get(1))
                .put("effectiveContentDigest", "e".repeat(64));
        when(catalog.bounds(EXPERIMENT)).thenReturn(Optional.of(bounds(manifest)));

        var variants = source.plan(EXPERIMENT).orElseThrow().variants();

        assertThat(variants.get(0).fit()).isNotEqualTo(variants.get(1).fit());
    }

    @Test
    void theRulesOfAVariantAreTheValuesOfItsEffectiveRulesAndTheirDigest() {
        var baseline = VariantRulesFixtures.baseline();
        when(catalog.variantRules(EXPERIMENT, "baseline")).thenReturn(Optional.of(baseline));

        var rules = source.rules(EXPERIMENT, "baseline").orElseThrow();

        assertThat(rules.digest()).isEqualTo(baseline.digest());
        assertThat(rules.properties())
                .containsOnlyKeys(baseline.properties().keySet().toArray(String[]::new));
        assertThat(rules.properties()).containsEntry("game.rules.win-score-threshold", new BigDecimal("20"));
    }

    @Test
    void aVariantWithoutRetainedRulesHasNone() {
        when(catalog.variantRules(EXPERIMENT, "candidate")).thenReturn(Optional.empty());

        assertThat(source.rules(EXPERIMENT, "candidate")).isEmpty();
    }

    private static io.github.temporalrift.workbench.experiment.ExperimentBounds bounds(
            tools.jackson.databind.JsonNode manifest) {
        return StoredManifests.bounds(new ExperimentRepository.StoredExperiment(
                EXPERIMENT, ManifestDigest.sha256Hex(manifest), manifest.toString(), "x", java.time.Instant.now()));
    }
}
