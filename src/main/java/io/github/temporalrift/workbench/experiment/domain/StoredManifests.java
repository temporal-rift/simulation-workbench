package io.github.temporalrift.workbench.experiment.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.ExperimentBounds;
import io.github.temporalrift.workbench.experiment.ExperimentVariant;
import io.github.temporalrift.workbench.experiment.FrozenManifest;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository.StoredExperiment;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.text.JsonText;

/** The one reader of a stored experiment's manifest text; every module-internal read of it goes through here. */
public final class StoredManifests {

    private StoredManifests() {}

    /** The manifest as a tree. */
    public static JsonNode tree(StoredExperiment stored) {
        return JsonText.readTree(stored.manifestJson(), "Stored manifest " + stored.experimentId());
    }

    /** The variant of the manifest with the label, or empty when it declares none. */
    public static Optional<JsonNode> variant(JsonNode manifest, String label) {
        return manifest.path("variants")
                .valueStream()
                .filter(candidate -> label.equals(candidate.path("label").asString()))
                .findFirst();
    }

    /** The execution bounds and the typed definition of the stored manifest. */
    public static ExperimentBounds bounds(StoredExperiment stored) {
        return bounds(stored, tree(stored));
    }

    /** The bounds read from the already parsed {@link #tree} of the stored manifest. */
    public static ExperimentBounds bounds(StoredExperiment stored, JsonNode tree) {
        var document = JsonText.convert(tree, Document.class, "Stored manifest " + stored.experimentId());
        var variants = document.variants().stream()
                .map(variant -> new ExperimentVariant(
                        variant.label(), variant.effectiveRulesDigest(), variant.effectiveContentDigest()))
                .toList();
        return new ExperimentBounds(
                stored.manifestDigest(),
                stored.manifestJson(),
                document.concurrency(),
                document.caseWallTimeoutSeconds(),
                document.maxRejectedCandidatesPerWindow(),
                variants,
                new FrozenManifest(
                        document.services(),
                        document.contracts(),
                        document.seatRotationMode(),
                        document.timingMode(),
                        document.maxRejectedCandidatesPerWindow(),
                        document.seeds(),
                        document.playerCounts(),
                        bundles(document),
                        tables(document),
                        document.factionSets(),
                        variants));
    }

    /** The policy bundles; an entry frozen before seat tables also carried a seat assignment that was never played. */
    private static List<Map<String, Object>> bundles(Document document) {
        return document.policies().stream()
                .map(policy -> {
                    var bundle = new LinkedHashMap<>(policy);
                    bundle.remove("seatAssignment");
                    return (Map<String, Object>) bundle;
                })
                .toList();
    }

    /** The seat tables, or one uniform table per policy entry for a manifest frozen before seat tables. */
    private static List<Map<String, Object>> tables(Document document) {
        if (document.tables() != null) {
            return document.tables();
        }
        int seats = document.playerCounts().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return document.policies().stream()
                .map(policy -> {
                    var assignments = IntStream.range(0, seats)
                            .mapToObj(seat -> {
                                var assignment = new LinkedHashMap<String, Object>();
                                assignment.put("seatIndex", seat);
                                assignment.put("policyId", policy.get("id"));
                                assignment.put("policyVersion", policy.get("version"));
                                return (Object) assignment;
                            })
                            .toList();
                    var table = new LinkedHashMap<String, Object>();
                    table.put("id", policy.get("id"));
                    table.put("seats", assignments);
                    return (Map<String, Object>) table;
                })
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(
            int concurrency,
            int caseWallTimeoutSeconds,
            int maxRejectedCandidatesPerWindow,
            Map<String, Object> services,
            Map<String, Object> contracts,
            String seatRotationMode,
            String timingMode,
            List<String> seeds,
            List<Integer> playerCounts,
            List<Map<String, Object>> policies,
            List<Map<String, Object>> tables,
            List<List<Faction>> factionSets,
            List<Variant> variants) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Variant(String label, String effectiveRulesDigest, String effectiveContentDigest) {}
}
