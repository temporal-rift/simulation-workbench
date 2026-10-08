package io.github.temporalrift.workbench.experiment;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;
import io.github.temporalrift.workbench.policy.domain.baseline.PolicyBundle;

/** Builds valid experiment manifests for tests. Faction vocabulary mirrors the shared enums. */
public final class ExperimentManifests {

    public static final List<String> FACTIONS = List.of("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExperimentManifests() {}

    public static JsonNode valid() {
        return valid("42", "a".repeat(64), "b".repeat(64));
    }

    public static JsonNode valid(String seed, String rulesDigest, String contentDigest) {
        var manifest = MAPPER.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("name", "threshold experiment");
        var variants = MAPPER.createArrayNode();
        variants.add(variant("baseline", rulesDigest, contentDigest));
        variants.add(variant("candidate", rulesDigest, contentDigest));
        manifest.set("variants", variants);
        manifest.set("services", services());
        var contracts = MAPPER.createObjectNode();
        contracts.put("session-api", "3.0.0");
        contracts.put("action-api", "7.1.0");
        manifest.set("contracts", contracts);
        var policies = MAPPER.createArrayNode();
        policies.add(policy(BaselinePolicies.RANDOM_V1));
        policies.add(policy(BaselinePolicies.FACTION_GREEDY_V1));
        manifest.set("policies", policies);
        var seeds = MAPPER.createArrayNode();
        seeds.add(seed);
        manifest.set("seeds", seeds);
        var counts = MAPPER.createArrayNode();
        counts.add(3);
        counts.add(4);
        counts.add(5);
        manifest.set("playerCounts", counts);
        manifest.set("factionSets", fullFactionSets());
        manifest.put("seatRotationMode", "CYCLIC");
        manifest.put("timingMode", "LOGICAL");
        manifest.put("concurrency", 4);
        manifest.put("caseWallTimeoutSeconds", 300);
        manifest.put("maxRejectedCandidatesPerWindow", 10);
        return manifest;
    }

    public static JsonNode singleSeedSinglePolicySingleVariant() {
        return singleSeedSinglePolicySingleVariant("42");
    }

    public static JsonNode singleSeedSinglePolicySingleVariant(String seed) {
        var manifest = (ObjectNode) valid(seed, "a".repeat(64), "b".repeat(64)).deepCopy();
        var variants = MAPPER.createArrayNode();
        variants.add(variant("only", "a".repeat(64), "b".repeat(64)));
        manifest.set("variants", variants);
        var policies = MAPPER.createArrayNode();
        policies.add(policy(BaselinePolicies.RANDOM_V1));
        manifest.set("policies", policies);
        return manifest;
    }

    /**
     * A manifest with exactly {@code seeds} x {@value #FOUR_PLAYER_CASES_PER_SEED} cases: one variant, one
     * policy, four players, every four-faction set under all four cyclic rotations.
     */
    public static JsonNode fourPlayerSeeds(int seeds, int concurrency) {
        var manifest = (ObjectNode) singleSeedSinglePolicySingleVariant().deepCopy();
        var seedValues = MAPPER.createArrayNode();
        for (var seed = 1; seed <= seeds; seed++) {
            seedValues.add(String.valueOf(seed));
        }
        manifest.set("seeds", seedValues);
        var counts = MAPPER.createArrayNode();
        counts.add(4);
        manifest.set("playerCounts", counts);
        var sets = MAPPER.createArrayNode();
        combinations(FACTIONS, 4).forEach(combo -> sets.add(set(combo)));
        manifest.set("factionSets", sets);
        manifest.put("concurrency", concurrency);
        return manifest;
    }

    /** One seed of three players in a single faction set: three cases, one per rotation. */
    public static JsonNode threePlayerSingleSet() {
        var manifest = (ObjectNode) singleSeedSinglePolicySingleVariant().deepCopy();
        var counts = MAPPER.createArrayNode();
        counts.add(3);
        manifest.set("playerCounts", counts);
        var sets = MAPPER.createArrayNode();
        sets.add(set(List.of("ERASERS", "PROPHETS", "WEAVERS")));
        manifest.set("factionSets", sets);
        return manifest;
    }

    public static final int FOUR_PLAYER_CASES_PER_SEED = 20;

    public static ArrayNode fullFactionSets() {
        var sets = MAPPER.createArrayNode();
        combinations(FACTIONS, 3).forEach(combo -> sets.add(set(combo)));
        combinations(FACTIONS, 4).forEach(combo -> sets.add(set(combo)));
        sets.add(set(FACTIONS));
        return sets;
    }

    static List<List<String>> combinations(List<String> factions, int size) {
        var result = new ArrayList<List<String>>();
        combine(factions, size, 0, new ArrayList<>(), result);
        return result;
    }

    private static void combine(
            List<String> factions, int size, int start, List<String> current, List<List<String>> result) {
        if (current.size() == size) {
            result.add(List.copyOf(current));
            return;
        }
        for (int index = start; index < factions.size(); index++) {
            current.add(factions.get(index));
            combine(factions, size, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private static ArrayNode set(List<String> factions) {
        var set = MAPPER.createArrayNode();
        factions.forEach(set::add);
        return set;
    }

    private static ObjectNode variant(String label, String rulesDigest, String contentDigest) {
        var variant = MAPPER.createObjectNode();
        variant.put("label", label);
        variant.set("rulesArtifact", artifact("c".repeat(64)));
        variant.set("contentArtifact", artifact("d".repeat(64)));
        variant.put("effectiveRulesDigest", rulesDigest);
        variant.put("effectiveContentDigest", contentDigest);
        return variant;
    }

    private static ObjectNode artifact(String digest) {
        var artifact = MAPPER.createObjectNode();
        artifact.put("digest", digest);
        return artifact;
    }

    private static ObjectNode services() {
        var services = MAPPER.createObjectNode();
        services.set("gameService", service());
        services.set("timelineService", service());
        services.set("readService", service());
        return services;
    }

    private static ObjectNode service() {
        var service = MAPPER.createObjectNode();
        service.put("imageDigest", "sha256:" + "e".repeat(64));
        service.put("sourceRevision", "abc1234");
        return service;
    }

    private static ObjectNode policy(PolicyBundle bundle) {
        var id = bundle.id();
        var version = bundle.version();
        var policy = MAPPER.createObjectNode();
        policy.put("id", id);
        policy.put("version", version);
        policy.put("artifactDigest", bundle.artifactDigest());
        policy.set("parameters", MAPPER.createObjectNode());
        var seats = MAPPER.createArrayNode();
        var seat = MAPPER.createObjectNode();
        seat.put("seatIndex", 0);
        seat.put("policyId", id);
        seat.put("policyVersion", version);
        seats.add(seat);
        policy.set("seatAssignment", seats);
        return policy;
    }
}
