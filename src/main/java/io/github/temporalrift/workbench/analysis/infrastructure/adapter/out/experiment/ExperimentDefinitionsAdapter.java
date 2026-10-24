package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.experiment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.comparison.ExperimentDefinition;
import io.github.temporalrift.workbench.analysis.domain.port.out.ExperimentDefinitions;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.FrozenManifest;
import io.github.temporalrift.workbench.shared.text.JsonText;

/**
 * Reads frozen manifests through the experiment module's public API and renders each gameplay-affecting definition
 * as canonical text. Sets (seeds, player counts, faction sets, policies) compare regardless of order, and object
 * keys are sorted, so only a change of meaning makes two definitions differ.
 */
public class ExperimentDefinitionsAdapter implements ExperimentDefinitions {

    private final ExperimentCatalog catalog;

    public ExperimentDefinitionsAdapter(ExperimentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<FrozenExperiment> find(UUID experimentId) {
        return catalog.bounds(experimentId)
                .map(bounds -> new FrozenExperiment(bounds.manifestDigest(), definition(bounds.manifest())));
    }

    private static ExperimentDefinition definition(FrozenManifest manifest) {
        var gameplay = new TreeMap<String, String>();
        gameplay.put("services", canonical(manifest.services()));
        gameplay.put("contracts", canonical(manifest.contracts()));
        gameplay.put("seatRotationMode", canonical(manifest.seatRotationMode()));
        gameplay.put("timingMode", canonical(manifest.timingMode()));
        gameplay.put("maxRejectedCandidatesPerWindow", canonical(manifest.maxRejectedCandidatesPerWindow()));
        gameplay.put("seeds", canonicalSet(manifest.seeds()));
        gameplay.put("playerCounts", canonicalSet(manifest.playerCounts()));
        gameplay.put("policies", canonicalSet(manifest.policies()));
        var factionSets = new ArrayList<String>();
        manifest.factionSets().forEach(set -> factionSets.add(canonicalSet(set)));
        gameplay.put("factionSets", sortedList(factionSets));
        var variants = new HashMap<String, ExperimentDefinition.VariantDigests>();
        manifest.variants()
                .forEach(variant -> variants.put(
                        variant.label(),
                        new ExperimentDefinition.VariantDigests(
                                variant.effectiveRulesDigest(), variant.effectiveContentDigest())));
        return new ExperimentDefinition(gameplay, variants);
    }

    private static String canonicalSet(List<?> items) {
        return sortedList(
                items.stream().map(ExperimentDefinitionsAdapter::canonical).toList());
    }

    private static String sortedList(List<String> items) {
        return items.stream().sorted().toList().toString();
    }

    private static String canonical(Object value) {
        return JsonText.canonical(value, "a frozen manifest definition");
    }
}
