package io.github.temporalrift.workbench.experiment.application.port.in;

import java.math.BigDecimal;
import java.util.Map;
import java.util.SortedMap;

import io.github.temporalrift.workbench.experiment.EffectiveRules;

/** Applies and validates a variant's overrides without persisting anything. */
public interface PreviewVariantRulesUseCase {

    Result handle(Query query);

    record Query(String rulesArtifactDigest, int parameterSpaceVersion, Map<String, BigDecimal> overrides) {}

    /** The effective rules, their digest, and the overrides as freezing would store them. */
    record Result(EffectiveRules rules, String effectiveRulesDigest, SortedMap<String, BigDecimal> overrides) {}
}
