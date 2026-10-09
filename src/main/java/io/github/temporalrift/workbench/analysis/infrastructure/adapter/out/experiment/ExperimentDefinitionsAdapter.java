package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.experiment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.domain.comparison.ExperimentDefinition;
import io.github.temporalrift.workbench.analysis.domain.port.out.ExperimentDefinitions;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;

/**
 * Reads frozen manifests through the experiment module's public API and renders each gameplay-affecting definition
 * as canonical text. Sets (seeds, player counts, faction sets, policies) compare regardless of order, and object
 * keys are sorted, so only a change of meaning makes two definitions differ.
 */
public class ExperimentDefinitionsAdapter implements ExperimentDefinitions {

    private static final List<String> AS_WRITTEN =
            List.of("services", "contracts", "seatRotationMode", "timingMode", "maxRejectedCandidatesPerWindow");
    private static final List<String> AS_SET = List.of("seeds", "playerCounts", "policies");
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final ExperimentCatalog catalog;

    public ExperimentDefinitionsAdapter(ExperimentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<FrozenExperiment> find(UUID experimentId) {
        return catalog.bounds(experimentId)
                .map(bounds -> new FrozenExperiment(bounds.manifestDigest(), definition(read(bounds.manifestJson()))));
    }

    private static ExperimentDefinition definition(JsonNode manifest) {
        var gameplay = new TreeMap<String, String>();
        AS_WRITTEN.forEach(field -> gameplay.put(field, canonical(manifest.path(field))));
        AS_SET.forEach(field -> gameplay.put(field, canonicalSet(manifest.path(field))));
        var factionSets = new ArrayList<String>();
        manifest.path("factionSets").forEach(set -> factionSets.add(canonicalSet(set)));
        gameplay.put("factionSets", sortedList(factionSets));
        var variants = new HashMap<String, ExperimentDefinition.VariantDigests>();
        manifest.path("variants")
                .forEach(variant -> variants.put(
                        variant.path("label").asString(),
                        new ExperimentDefinition.VariantDigests(
                                variant.path("effectiveRulesDigest").asString(),
                                variant.path("effectiveContentDigest").asString())));
        return new ExperimentDefinition(gameplay, variants);
    }

    private static String canonicalSet(JsonNode array) {
        var items = new ArrayList<String>();
        array.forEach(item -> items.add(canonical(item)));
        return sortedList(items);
    }

    private static String sortedList(List<String> items) {
        return items.stream().sorted().toList().toString();
    }

    private static String canonical(JsonNode node) {
        try {
            return CANONICAL.writeValueAsString(CANONICAL.treeToValue(node, Object.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("A frozen manifest definition cannot be rendered", e);
        }
    }

    private static JsonNode read(String manifestJson) {
        try {
            return CANONICAL.readTree(manifestJson);
        } catch (JacksonException e) {
            throw new IllegalStateException("A frozen manifest is not JSON", e);
        }
    }
}
