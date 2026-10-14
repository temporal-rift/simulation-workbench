package io.github.temporalrift.workbench.browser;

import java.util.ArrayList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The form's unsent-draft representation of a manifest, so a browser test starts from a filled-in form. */
final class DraftJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftJson() {}

    /** The value the application keeps for an unsent draft in the tab's session storage. */
    static String storedDraft(JsonNode manifest) {
        var draft = MAPPER.createObjectNode();
        draft.put("name", manifest.get("name").asString());
        var variants = draft.putArray("variants");
        manifest.get("variants").forEach(variant -> {
            var entry = variants.addObject();
            entry.put("label", variant.get("label").asString());
            var bundle = entry.putObject("bundle");
            bundle.set("rulesArtifact", variant.get("rulesArtifact"));
            bundle.set("contentArtifact", variant.get("contentArtifact"));
            bundle.set("effectiveRulesDigest", variant.get("effectiveRulesDigest"));
            bundle.set("effectiveContentDigest", variant.get("effectiveContentDigest"));
            bundle.set("services", manifest.get("services"));
            bundle.set("contracts", manifest.get("contracts"));
        });
        var policies = draft.putArray("policies");
        manifest.get("policies").forEach(policy -> {
            var entry = policies.addObject();
            entry.put("id", policy.get("id").asString());
            entry.put("version", policy.get("version").asString());
            entry.put("artifactDigest", policy.get("artifactDigest").asString());
            entry.put("parameters", MAPPER.writeValueAsString(policy.get("parameters")));
            var seats = entry.putArray("seats");
            policy.get("seatAssignment")
                    .forEach(seat -> seats.add(seat.get("seatIndex").asString()));
        });
        var seeds = new ArrayList<String>();
        manifest.get("seeds").forEach(seed -> seeds.add(seed.asString()));
        draft.put("seeds", String.join("\n", seeds));
        draft.set("playerCounts", manifest.get("playerCounts"));
        draft.set("factionSets", manifest.get("factionSets"));
        draft.put("concurrency", manifest.get("concurrency").asString());
        draft.put(
                "caseWallTimeoutSeconds", manifest.get("caseWallTimeoutSeconds").asString());
        draft.put(
                "maxRejectedCandidatesPerWindow",
                manifest.get("maxRejectedCandidatesPerWindow").asString());

        var stored = (ObjectNode) MAPPER.createObjectNode();
        stored.set("draft", draft);
        stored.putNull("basedOn");
        return MAPPER.writeValueAsString(stored);
    }
}
