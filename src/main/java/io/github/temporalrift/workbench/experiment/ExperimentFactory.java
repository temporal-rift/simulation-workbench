package io.github.temporalrift.workbench.experiment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Lets another module prepare and freeze an experiment it composes itself. */
public interface ExperimentFactory {

    /**
     * The rules a variant would carry, without persisting anything: the digest to name in the manifest and the
     * overrides as freezing would store them.
     *
     * @throws ExperimentRefusedException when the baseline, the parameter space or an override is refused
     */
    VariantPreview previewVariant(
            String rulesArtifactDigest, int parameterSpaceVersion, Map<String, BigDecimal> overrides);

    /**
     * Freezes the manifest once per idempotency key and returns the experiment's id; the same key with the same
     * manifest returns the original experiment.
     *
     * @throws ExperimentRefusedException when the manifest is refused or the key was used for another manifest
     */
    UUID freeze(UUID idempotencyKey, JsonNode manifest);

    /** A variant's effective rules digest and its normalised overrides. */
    record VariantPreview(String effectiveRulesDigest, SortedMap<String, BigDecimal> overrides) {}
}
