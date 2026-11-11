package io.github.temporalrift.workbench.experiment.domain.rules;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/**
 * Resolves the rules a variant stands for: a baseline rules document some lane publishes, plus overrides checked
 * against a parameter space. A variant without overrides whose baseline no lane publishes is accepted as it is,
 * because it names rules by digest alone.
 */
public final class VariantRules {

    private static final String PARAMETER_SPACE_VERSION = "parameterSpaceVersion";
    private static final String NO_BASELINE = "no lane publishes the baseline rules artifact ";
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

    /**
     * A declared baseline that lacks what a parameter space needs.
     *
     * @param scope the property prefix naming the deployment that declares the baseline
     * @param missing each missing key with its type
     */
    public record BaselineGap(String scope, List<String> missing) {}

    /**
     * The declared baselines that cannot serve the parameter space, with what each lacks. Empty when the space is not
     * served or every baseline carries what it needs.
     */
    public List<BaselineGap> baselineGaps(int parameterSpaceVersion) {
        return spaces.find(parameterSpaceVersion).stream()
                .flatMap(space -> baselines.declared().stream()
                        .map(declared -> new BaselineGap(
                                declared.scope(),
                                VariantMaterializer.missing(declared.baseline().rules(), space).stream()
                                        .map(entry -> entry.getValue() + " " + entry.getKey())
                                        .toList())))
                .filter(gap -> !gap.missing().isEmpty())
                .toList();
    }

    /** The effective rules a request for a variant stands for. */
    public record Preview(EffectiveRules rules, String digest, SortedMap<String, ParameterValue> overrides) {}

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
        SortedMap<String, ParameterValue> overrides;
        try {
            overrides = overridesOf(variant);
        } catch (VariantRulesRefusal refusal) {
            throw refusal.at(ExperimentErrorCode.INVALID_EXPERIMENT, path);
        }
        var artifactDigest = variant.path("rulesArtifact").path("digest").asString();
        var baseline = baselines.find(artifactDigest);
        if (baseline.isEmpty()) {
            if (!overrides.isEmpty()) {
                throw new VariantRulesRefusal(
                                VariantRulesReason.UNKNOWN_BASELINE, NO_BASELINE + artifactDigest, null, null)
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
            Baseline baseline, JsonNode version, Map<String, ParameterValue> overrides, String path) {
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
    public Preview preview(
            String rulesArtifactDigest, int parameterSpaceVersion, Map<String, ParameterValue> overrides) {
        try {
            var space = space(parameterSpaceVersion);
            var baseline = baselines
                    .find(rulesArtifactDigest)
                    .orElseThrow(() -> new VariantRulesRefusal(
                            VariantRulesReason.UNKNOWN_BASELINE, NO_BASELINE + rulesArtifactDigest, null, null));
            var materialized = VariantMaterializer.materialize(baseline.rules(), space, overrides);
            return new Preview(materialized.rules(), materialized.digest(), materialized.overrides());
        } catch (VariantRulesRefusal refusal) {
            throw refusal.at(ExperimentErrorCode.INVALID_REQUEST, "");
        }
    }

    /** One overridable parameter with the value the baseline rules carry. */
    public sealed interface Axis permits NumberAxis, BooleanAxis {

        String key();
    }

    /** A numeric parameter: the values {@code min}, {@code min + step}, ... up to {@code max}. */
    public record NumberAxis(String key, BigDecimal min, BigDecimal max, BigDecimal step, BigDecimal baseline)
            implements Axis {}

    /** A boolean parameter: the values {@code false} and {@code true}. */
    public record BooleanAxis(String key, boolean baseline) implements Axis {}

    /** The parameters of a space that overrides may set, by key, with the values a baseline publishes for them. */
    public List<Axis> axes(String rulesArtifactDigest, int parameterSpaceVersion) {
        try {
            var space = space(parameterSpaceVersion);
            var baseline = baselines
                    .find(rulesArtifactDigest)
                    .orElseThrow(() -> new VariantRulesRefusal(
                            VariantRulesReason.UNKNOWN_BASELINE, NO_BASELINE + rulesArtifactDigest, null, null));
            return space.parameters().stream()
                    .sorted(Comparator.comparing(ParameterSpace.Parameter::key))
                    .map(parameter -> axis(parameter, baseline.rules()))
                    .toList();
        } catch (VariantRulesRefusal refusal) {
            throw refusal.at(ExperimentErrorCode.INVALID_REQUEST, "");
        }
    }

    private static Axis axis(ParameterSpace.Parameter parameter, EffectiveRules baseline) {
        return switch (parameter) {
            case ParameterSpace.NumberParameter number ->
                new NumberAxis(
                        number.key(),
                        number.min(),
                        number.max(),
                        number.step(),
                        baseline.number(number.key()).orElseThrow(() -> noBaselineValue(number.key(), "number")));
            case ParameterSpace.BooleanParameter flag ->
                new BooleanAxis(
                        flag.key(),
                        baseline.flag(flag.key()).orElseThrow(() -> noBaselineValue(flag.key(), "boolean")));
        };
    }

    private static VariantRulesRefusal noBaselineValue(String key, String type) {
        return new VariantRulesRefusal(
                VariantRulesReason.UNKNOWN_BASELINE, "the baseline rules carry no " + type + " at " + key, null, null);
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

    /**
     * The overrides a variant node carries, by key; empty when it has none. A value that is neither a number nor a
     * boolean is refused here; whether the type fits its parameter is decided against the parameter space.
     *
     * @throws VariantRulesRefusal with {@link VariantRulesReason#WRONG_TYPE} for a value that is neither
     */
    public static SortedMap<String, ParameterValue> overridesOf(JsonNode variant) {
        var overrides = new TreeMap<String, ParameterValue>();
        var node = variant.get(OVERRIDES);
        if (node != null && node.isObject()) {
            node.properties().forEach(entry -> {
                if (!entry.getValue().isNumber() && !entry.getValue().isBoolean()) {
                    throw new VariantRulesRefusal(
                            VariantRulesReason.WRONG_TYPE,
                            "override " + entry.getKey() + " must be a number or a boolean",
                            entry.getKey(),
                            null);
                }
                overrides.put(entry.getKey(), ParameterValue.of(entry.getValue()));
            });
        }
        return overrides;
    }

    private static void writeOverrides(ObjectNode variant, Map<String, ParameterValue> overrides) {
        variant.remove(OVERRIDES);
        if (overrides.isEmpty()) {
            variant.remove(PARAMETER_SPACE_VERSION);
            return;
        }
        var node = variant.putObject(OVERRIDES);
        new TreeMap<>(overrides).forEach((key, value) -> node.set(key, value.json()));
    }
}
