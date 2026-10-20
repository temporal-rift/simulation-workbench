package io.github.temporalrift.workbench.experiment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.domain.port.out.ParameterSpaces;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.configuration.ConfiguredParameterSpaces;

/** The baseline rules document the lanes of the tests serve, and the variant rules built around it. */
public final class VariantRulesFixtures {

    /** The digest of the rules artifact that names the baseline document. */
    public static final String ARTIFACT_DIGEST = "e".repeat(64);

    private static final String RESOURCE = "/rules/baseline-effective-rules.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VariantRulesFixtures() {}

    /** The baseline document exactly as the lanes publish it. */
    public static String baselineText() {
        try (var in = VariantRulesFixtures.class.getResourceAsStream(RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static EffectiveRules baseline() {
        return EffectiveRules.parse(MAPPER.readTree(baselineText()));
    }

    public static ParameterSpaces spaces() {
        return new ConfiguredParameterSpaces(MAPPER);
    }

    /** A catalog whose one baseline is named by {@link #ARTIFACT_DIGEST}. */
    public static BaselineRulesCatalog catalog() {
        var baseline = new BaselineRulesCatalog.Baseline(baselineText(), baseline());
        return digest -> ARTIFACT_DIGEST.equals(digest) ? Optional.of(baseline) : Optional.empty();
    }

    /** A variant over the baseline: its overrides, and the digest it claims for the rules they make. */
    public static ObjectNode variant(String label, String effectiveRulesDigest, String overridesJson) {
        var variant = ExperimentManifests.variant(label, effectiveRulesDigest, "b".repeat(64));
        ((ObjectNode) variant.get("rulesArtifact")).put("digest", ARTIFACT_DIGEST);
        var overrides = MAPPER.readTree(overridesJson);
        if (!overrides.isEmpty()) {
            variant.set("overrides", overrides);
            variant.put("parameterSpaceVersion", 1);
        }
        return variant;
    }

    /** The test manifest with the given variants. */
    public static ObjectNode manifest(ObjectNode... variants) {
        var manifest = (ObjectNode) ExperimentManifests.valid("42", "a".repeat(64), "b".repeat(64));
        var array = MAPPER.createArrayNode();
        Arrays.stream(variants).forEach(array::add);
        manifest.set("variants", array);
        return manifest;
    }

    /** Variant rules over the shipped parameter spaces and the test baseline. */
    public static VariantRules variantRules() {
        return new VariantRules(spaces(), catalog());
    }

    /** Variant rules for tests of experiments that name rules by digest alone: no lane publishes any baseline. */
    public static VariantRules withoutBaselines() {
        return new VariantRules(spaces(), digest -> Optional.empty());
    }
}
