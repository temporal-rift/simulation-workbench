package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a frozen experiment fixes for gameplay. {@code gameplay} maps each gameplay-affecting definition (services,
 * contracts, policies, seeds, player counts, faction sets, seat rotation, timing, rejection budget) to a canonical
 * text, so two experiments compare definition by definition.
 */
public record ExperimentDefinition(SortedMap<String, String> gameplay, Map<String, VariantDigests> variants) {

    public ExperimentDefinition {
        gameplay = new TreeMap<>(gameplay);
        variants = Map.copyOf(variants);
    }

    /** The effective rules and content digests of a variant. */
    public record VariantDigests(String rules, String content) {}
}
