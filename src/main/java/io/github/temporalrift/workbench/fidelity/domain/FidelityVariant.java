package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/**
 * A variant a check plays: the rules it means and the normalised overrides on the baseline that make them; none for
 * the baseline itself.
 */
public record FidelityVariant(
        VariantLabel label, String effectiveRulesDigest, SortedMap<String, ParameterValue> overrides) {

    public FidelityVariant {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(effectiveRulesDigest, "effectiveRulesDigest");
        overrides = Collections.unmodifiableSortedMap(new TreeMap<>(overrides));
    }
}
