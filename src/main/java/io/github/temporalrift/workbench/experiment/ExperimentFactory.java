package io.github.temporalrift.workbench.experiment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** Lets another module prepare and freeze an experiment it composes itself. */
public interface ExperimentFactory {

    /** The newest parameter space version the service serves. */
    int newestParameterSpaceVersion();

    /**
     * The rules a variant would carry, without persisting anything: the digest to name in the manifest and the
     * overrides as freezing would store them.
     *
     * @throws ExperimentRefusedException when the baseline, the parameter space or an override is refused
     */
    VariantPreview previewVariant(
            String rulesArtifactDigest, int parameterSpaceVersion, Map<String, ParameterValue> overrides);

    /**
     * The parameters of a parameter space that overrides may set, in key order, each with its range, its step and the
     * value the baseline rules carry.
     *
     * @throws ExperimentRefusedException when the baseline or the parameter space is unknown
     */
    List<ParameterAxis> parameterAxes(String rulesArtifactDigest, int parameterSpaceVersion);

    /**
     * Freezes the manifest once per idempotency key and returns the experiment's id; the same key with the same
     * manifest returns the original experiment.
     *
     * @throws ExperimentRefusedException when the manifest is refused or the key was used for another manifest
     */
    UUID freeze(UUID idempotencyKey, JsonNode manifest);

    /** One overridable parameter. */
    sealed interface ParameterAxis permits NumberAxis, BooleanAxis {

        String key();
    }

    /** A numeric parameter: the values {@code min}, {@code min + step}, ... up to {@code max}. */
    record NumberAxis(String key, BigDecimal min, BigDecimal max, BigDecimal step, BigDecimal baseline)
            implements ParameterAxis {}

    /** A boolean parameter: the values {@code false} and {@code true}. */
    record BooleanAxis(String key, boolean baseline) implements ParameterAxis {}

    /** A variant's effective rules digest and its normalised overrides. */
    record VariantPreview(String effectiveRulesDigest, SortedMap<String, ParameterValue> overrides) {}
}
