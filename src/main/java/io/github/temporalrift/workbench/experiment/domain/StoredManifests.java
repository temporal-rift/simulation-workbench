package io.github.temporalrift.workbench.experiment.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        var document = JsonText.read(stored.manifestJson(), Document.class, "Stored manifest " + stored.experimentId());
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
                        document.policies(),
                        document.factionSets(),
                        variants));
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
            List<List<Faction>> factionSets,
            List<Variant> variants) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Variant(String label, String effectiveRulesDigest, String effectiveContentDigest) {}
}
