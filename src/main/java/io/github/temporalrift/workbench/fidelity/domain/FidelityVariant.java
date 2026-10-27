package io.github.temporalrift.workbench.fidelity.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A variant a check plays: the rules it means and the normalised overrides on the baseline that make them; none for
 * the baseline itself.
 */
public record FidelityVariant(
        VariantLabel label, String effectiveRulesDigest, SortedMap<String, BigDecimal> overrides) {

    public FidelityVariant {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(effectiveRulesDigest, "effectiveRulesDigest");
        overrides = Collections.unmodifiableSortedMap(new TreeMap<>(overrides));
    }
}
