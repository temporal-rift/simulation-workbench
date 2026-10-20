package io.github.temporalrift.workbench.experiment.domain.rules;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.BaselineRulesCatalog;
import io.github.temporalrift.workbench.experiment.BaselineRulesCatalog.Baseline;
import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;
import io.github.temporalrift.workbench.experiment.domain.port.out.ParameterSpaces;

/**
 * Resolves the rules a variant stands for: a baseline rules document some lane publishes, plus overrides checked
 * against a parameter space. A variant without overrides whose baseline no lane publishes is accepted as it is,
 * because it names rules by digest alone.
 */
public final class VariantRules {

    private static final String PARAMETER_SPACE_VERSION = "parameterSpaceVersion";
    private static final String OVERRIDES = "overrides";

    private final ParameterSpaces spaces;
    private final BaselineRulesCatalog baselines;

    public VariantRules(ParameterSpaces spaces, BaselineRulesCatalog baselines) {
        this.spaces = spaces;
        this.baselines = baselines;
    }

    /**
     * A manifest with every variant's overrides normalised, and the baseline documents its variants use, by the
     * digest of the rules artifact that names them.
     */
    public record Frozen(JsonNode manifest, Map<String, String> baselineDocuments) {}

    /** The effective rules a request for a variant stands for. */
    public record Preview(EffectiveRules rules, String digest, SortedMap<String, BigDecimal> overrides) {}

    /**
     * Normalises the overrides of every variant of a structurally valid manifest: those equal to the baseline are
     * dropped and the rest are sorted by key, so that variants meaning the same rules freeze the same bytes. Each
     * variant whose baseline some lane publishes must carry the digest of its materialised rules.
     *
     * @throws ExperimentValidationException on the first variant that is refused
     */
    public Frozen freeze(JsonNode manifest) {
        var normalized = (ObjectNode) manifest.deepCopy();
        var retained = new LinkedHashMap<String, String>();
        var variants = (ArrayNode) normalized.get("variants");
        for (int i = 0; i < variants.size(); i++) {
            freezeVariant((ObjectNode) variants.get(i), "variants[" + i + "].", retained);
        }
        return new Frozen(normalized, retained);
    }

    private void freezeVariant(ObjectNode variant, String path, Map<String, String> retained) {
        var overrides = overridesOf(variant);
        var artifactDigest = variant.path("rulesArtifact").path("digest").asString();
        var baseline = baselines.find(artifactDigest);
        if (baseline.isEmpty()) {
            if (!overrides.isEmpty()) {
                throw new VariantRulesRefusal(
                                VariantRulesReason.UNKNOWN_BASELINE,
                                "no lane publishes the baseline rules artifact " + artifactDigest,
                                null,
                                null)
                        .at(ExperimentErrorCode.INVALID_EXPERIMENT, path);
            }
            writeOverrides(variant, overrides);
            return;
        }
        retained.putIfAbsent(artifactDigest, baseline.get().document());
        var materialized = materialize(baseline.get(), variant.path(PARAMETER_SPACE_VERSION), overrides, path);
        var supplied = variant.path("effectiveRulesDigest").asString();
        if (!materialized.digest().equals(supplied)) {
            throw new ExperimentValidationException(
                    ExperimentErrorCode.MANIFEST_MISMATCH,
                    "effectiveRulesDigest must be the digest of the baseline with the overrides applied: expected "
                            + materialized.digest(),
                    path + "effectiveRulesDigest");
        }
        writeOverrides(variant, materialized.overrides());
    }

    private VariantMaterializer.Materialized materialize(
            Baseline baseline, JsonNode version, Map<String, BigDecimal> overrides, String path) {
        try {
            if (overrides.isEmpty()) {
                return new VariantMaterializer.Materialized(baseline.rules(), new TreeMap<>());
            }
            return VariantMaterializer.materialize(baseline.rules(), space(version.asInt()), overrides);
        } catch (VariantRulesRefusal refusal) {
            throw refusal.at(ExperimentErrorCode.INVALID_EXPERIMENT, path);
        }
    }

    /** The rules of a request that names a baseline directly, without a manifest around it. */
    public Preview preview(String rulesArtifactDigest, int parameterSpaceVersion, Map<String, BigDecimal> overrides) {
        try {
            var space = space(parameterSpaceVersion);
            var baseline = baselines
                    .find(rulesArtifactDigest)
                    .orElseThrow(() -> new VariantRulesRefusal(
                            VariantRulesReason.UNKNOWN_BASELINE,
                            "no lane publishes the baseline rules artifact " + rulesArtifactDigest,
                            null,
                            null));
            var materialized = VariantMaterializer.materialize(baseline.rules(), space, overrides);
            return new Preview(materialized.rules(), materialized.digest(), materialized.overrides());
        } catch (VariantRulesRefusal refusal) {
            throw refusal.at(ExperimentErrorCode.INVALID_REQUEST, "");
        }
    }

    /** The effective rules of a frozen variant, from the baseline document retained for it. */
    public EffectiveRules frozenRules(EffectiveRules baseline, JsonNode variant) {
        var overrides = overridesOf(variant);
        if (overrides.isEmpty()) {
            return baseline;
        }
        var space = spaces.find(variant.path(PARAMETER_SPACE_VERSION).asInt())
                .orElseThrow(() -> new IllegalStateException("A frozen variant names a parameter space that is gone"));
        return VariantMaterializer.materialize(baseline, space, overrides).rules();
    }

    private ParameterSpace space(int version) {
        return spaces.find(version)
                .orElseThrow(() -> new VariantRulesRefusal(
                        VariantRulesReason.UNKNOWN_PARAMETER_SPACE,
                        "parameter space " + version + " is not served",
                        null,
                        null));
    }

    /** The overrides a variant node carries, by key; empty when it has none. */
    public static SortedMap<String, BigDecimal> overridesOf(JsonNode variant) {
        var overrides = new TreeMap<String, BigDecimal>();
        var node = variant.get(OVERRIDES);
        if (node != null && node.isObject()) {
            node.properties()
                    .forEach(entry ->
                            overrides.put(entry.getKey(), entry.getValue().decimalValue()));
        }
        return overrides;
    }

    private static void writeOverrides(ObjectNode variant, Map<String, BigDecimal> overrides) {
        variant.remove(OVERRIDES);
        if (overrides.isEmpty()) {
            variant.remove(PARAMETER_SPACE_VERSION);
            return;
        }
        var node = variant.putObject(OVERRIDES);
        new TreeMap<>(overrides).forEach((key, value) -> node.set(key, VariantMaterializer.number(value)));
    }
}
