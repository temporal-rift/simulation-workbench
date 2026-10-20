package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;

class VariantRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String THRESHOLD = "game.rules.win-score-threshold";
    private static final String PUSH_II = "game.rules.probability.push-shift.II";

    private final VariantRules rules = VariantRulesFixtures.variantRules();
    private final EffectiveRules baseline = VariantRulesFixtures.baseline();

    /** A manifest of two variants over the baseline: the first without overrides, the second with the given ones. */
    private ObjectNode manifest(String overridesJson) {
        return VariantRulesFixtures.manifest(
                VariantRulesFixtures.variant("baseline", baseline.digest(), "{}"),
                VariantRulesFixtures.variant("candidate", digestWith(overridesJson), overridesJson));
    }

    private String digestWith(String overridesJson) {
        var overrides = new java.util.TreeMap<String, BigDecimal>();
        MAPPER.readTree(overridesJson)
                .properties()
                .forEach(e -> overrides.put(e.getKey(), e.getValue().decimalValue()));
        return overrides.isEmpty()
                ? baseline.digest()
                : rules.preview(VariantRulesFixtures.ARTIFACT_DIGEST, 1, overrides)
                        .digest();
    }

    private static JsonNode variantAt(VariantRules.Frozen frozen, int index) {
        return frozen.manifest().get("variants").get(index);
    }

    @Test
    void overridesEqualToTheBaselineAreDroppedWhenFreezing() {
        var frozen = rules.freeze(manifest("{\"" + THRESHOLD + "\": 20}"));

        assertThat(variantAt(frozen, 1).has("overrides")).isFalse();
        assertThat(variantAt(frozen, 1).has("parameterSpaceVersion")).isFalse();
        assertThat(variantAt(frozen, 1).get("effectiveRulesDigest").asString()).isEqualTo(baseline.digest());
    }

    @Test
    void overridesAreFrozenSortedWithNormalisedNumbers() {
        var frozen = rules.freeze(manifest("{\"" + THRESHOLD + "\": 22, \"" + PUSH_II + "\": 25.0}"));

        var overrides = variantAt(frozen, 1).get("overrides");
        assertThat(overrides.propertyNames()).containsExactly(PUSH_II, THRESHOLD);
        assertThat(overrides).hasToString("{\"" + PUSH_II + "\":25,\"" + THRESHOLD + "\":22}");
        assertThat(variantAt(frozen, 1).get("parameterSpaceVersion").asInt()).isEqualTo(1);
    }

    @Test
    void variantsMeaningTheSameRulesFreezeTheSameBytesWhateverTheOrder() {
        var forward = rules.freeze(manifest("{\"" + THRESHOLD + "\": 22, \"" + PUSH_II + "\": 25}"));
        var reverse = rules.freeze(manifest("{\"" + PUSH_II + "\": 25, \"" + THRESHOLD + "\": 22}"));

        assertThat(variantAt(reverse, 1)).isEqualTo(variantAt(forward, 1));
        assertThat(ManifestDigest.sha256Hex(reverse.manifest()))
                .isEqualTo(ManifestDigest.sha256Hex(forward.manifest()));
    }

    @Test
    void theFrozenVariantCarriesTheDigestOfItsMaterialisedRules() {
        var frozen = rules.freeze(manifest("{\"" + PUSH_II + "\": 25}"));

        var overrides = VariantRules.overridesOf(variantAt(frozen, 1));
        var materialised = rules.frozenRules(baseline, variantAt(frozen, 1));
        assertThat(materialised.digest())
                .isEqualTo(variantAt(frozen, 1).get("effectiveRulesDigest").asString())
                .isNotEqualTo(baseline.digest());
        assertThat(overrides).containsOnlyKeys(PUSH_II);
    }

    @Test
    void aFrozenVariantWithoutOverridesIsServedAsItsBaseline() {
        var frozen = rules.freeze(manifest("{\"" + PUSH_II + "\": 25}"));

        assertThat(rules.frozenRules(baseline, variantAt(frozen, 0))).isSameAs(baseline);
    }

    @Test
    void theBaselineDocumentsOfTheVariantsAreRetainedOnce() {
        var frozen = rules.freeze(manifest("{\"" + PUSH_II + "\": 25}"));

        assertThat(frozen.baselineDocuments())
                .containsOnlyKeys(VariantRulesFixtures.ARTIFACT_DIGEST)
                .containsValue(VariantRulesFixtures.baselineText());
    }

    @Test
    void aSuppliedDigestThatDiffersIsAMismatch() {
        var manifest = manifest("{\"" + PUSH_II + "\": 25}");
        ((ObjectNode) manifest.get("variants").get(1)).put("effectiveRulesDigest", "f".repeat(64));

        assertThatThrownBy(() -> rules.freeze(manifest))
                .isInstanceOfSatisfying(ExperimentValidationException.class, e -> {
                    assertThat(e.code()).isEqualTo(ExperimentErrorCode.MANIFEST_MISMATCH);
                    assertThat(e.field()).contains("variants[1].effectiveRulesDigest");
                });
    }

    @Test
    void aVariantWithoutOverridesMustCarryTheDigestOfItsBaseline() {
        var manifest = manifest("{}");
        ((ObjectNode) manifest.get("variants").get(0)).put("effectiveRulesDigest", "f".repeat(64));

        assertThatThrownBy(() -> rules.freeze(manifest))
                .isInstanceOfSatisfying(
                        ExperimentValidationException.class,
                        e -> assertThat(e.field()).contains("variants[0].effectiveRulesDigest"));
    }

    @Test
    void aRefusedValueIsReportedAtItsPathInTheManifest() {
        var manifest = manifest("{\"" + THRESHOLD + "\": 22}");
        ((ObjectNode) manifest.get("variants").get(1)).set("overrides", MAPPER.readTree("{\"" + THRESHOLD + "\": 27}"));

        assertThatThrownBy(() -> rules.freeze(manifest))
                .isInstanceOfSatisfying(ExperimentValidationException.class, e -> {
                    assertThat(e.code()).isEqualTo(ExperimentErrorCode.INVALID_EXPERIMENT);
                    assertThat(e.reason()).contains(VariantRulesReason.OUT_OF_RANGE);
                    assertThat(e.field()).contains("variants[1].overrides." + THRESHOLD);
                });
    }

    @Test
    void theFirstRefusedVariantIsReported() {
        var manifest = manifest("{\"" + THRESHOLD + "\": 22}");
        var variants = (tools.jackson.databind.node.ArrayNode) manifest.get("variants");
        variants.add(VariantRulesFixtures.variant("third", "f".repeat(64), "{\"game.rules.max-eras\": 4}"));
        ((ObjectNode) variants.get(1)).set("overrides", MAPPER.readTree("{\"" + THRESHOLD + "\": 27}"));

        assertThatThrownBy(() -> rules.freeze(manifest))
                .isInstanceOfSatisfying(
                        ExperimentValidationException.class,
                        e -> assertThat(e.field()).contains("variants[1].overrides." + THRESHOLD));
    }

    @Test
    void overridesNeedABaselineSomeLanePublishes() {
        var manifest = manifest("{\"" + PUSH_II + "\": 25}");
        ((ObjectNode) manifest.get("variants").get(1).get("rulesArtifact")).put("digest", "9".repeat(64));

        assertThatThrownBy(() -> rules.freeze(manifest))
                .isInstanceOfSatisfying(ExperimentValidationException.class, e -> {
                    assertThat(e.reason()).contains(VariantRulesReason.UNKNOWN_BASELINE);
                    assertThat(e.field()).contains("variants[1].rulesArtifact.digest");
                });
    }

    @Test
    void aVariantWithoutOverridesNeedsNoPublishedBaseline() {
        var unpublished = ExperimentManifests.valid();

        var frozen = VariantRulesFixtures.withoutBaselines().freeze(unpublished);

        assertThat(frozen.baselineDocuments()).isEmpty();
        assertThat(frozen.manifest()).isEqualTo(unpublished);
    }

    @Test
    void anUnservedParameterSpaceIsRefused() {
        var manifest = manifest("{\"" + PUSH_II + "\": 25}");
        ((ObjectNode) manifest.get("variants").get(1)).put("parameterSpaceVersion", 7);

        assertThatThrownBy(() -> rules.freeze(manifest))
                .isInstanceOfSatisfying(ExperimentValidationException.class, e -> {
                    assertThat(e.reason()).contains(VariantRulesReason.UNKNOWN_PARAMETER_SPACE);
                    assertThat(e.field()).contains("variants[1].parameterSpaceVersion");
                });
    }

    @Test
    void aPreviewIsARefusalOfTheRequestNotOfAnExperiment() {
        var overrides = Map.of(THRESHOLD, new BigDecimal("27"));

        assertThatThrownBy(() -> rules.preview(VariantRulesFixtures.ARTIFACT_DIGEST, 1, overrides))
                .isInstanceOfSatisfying(ExperimentValidationException.class, e -> {
                    assertThat(e.code()).isEqualTo(ExperimentErrorCode.INVALID_REQUEST);
                    assertThat(e.field()).contains("overrides." + THRESHOLD);
                });
    }

    @Test
    void aPreviewOfAnUnknownBaselineNamesTheArtifact() {
        assertThatThrownBy(() -> rules.preview(
                        UUID.randomUUID().toString().replace("-", "").repeat(2), 1, Map.of()))
                .isInstanceOfSatisfying(ExperimentValidationException.class, e -> {
                    assertThat(e.reason()).contains(VariantRulesReason.UNKNOWN_BASELINE);
                    assertThat(e.field()).contains("rulesArtifact.digest");
                });
    }

    @Test
    void aPreviewWithoutOverridesIsTheBaseline() {
        var preview = rules.preview(VariantRulesFixtures.ARTIFACT_DIGEST, 1, Map.of());

        assertThat(preview.digest()).isEqualTo(baseline.digest());
        assertThat(preview.overrides()).isEmpty();
    }
}
