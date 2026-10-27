package io.github.temporalrift.workbench.fidelity.domain.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Prepares and freezes the experiment a check plays. */
public interface FidelityExperiments {

    /**
     * The rules a variant would carry.
     *
     * Refused with a fidelity refusal exception when the baseline, the parameter space or an override is refused
     */
    VariantRules previewVariant(
            String rulesArtifactDigest, int parameterSpaceVersion, Map<String, BigDecimal> overrides);

    /** The baseline policies a manifest references, by id, each at the version the matrix plays. */
    List<Policy> baselinePolicies();

    /**
     * Freezes the manifest once per idempotency key and returns the experiment id.
     *
     * Refused with a fidelity refusal exception when the manifest is refused
     */
    UUID freeze(UUID idempotencyKey, JsonNode manifest);

    record VariantRules(String effectiveRulesDigest, SortedMap<String, BigDecimal> overrides) {}

    record Policy(String id, String version, String artifactDigest) {}
}
