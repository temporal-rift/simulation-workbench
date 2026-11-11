package io.github.temporalrift.workbench.search.domain.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** Materialises overrides on the campaign's baseline exactly as an experiment variant is. */
public interface CandidateValidation {

    /** The effective rules the overrides make, or empty when they break a range, a step or a constraint. */
    Optional<Validated> validate(Map<String, ParameterValue> overrides);

    /**
     * @param overrides the overrides that remain once those equal to the baseline are dropped
     */
    record Validated(String effectiveRulesDigest, SortedMap<String, ParameterValue> overrides) {}
}
