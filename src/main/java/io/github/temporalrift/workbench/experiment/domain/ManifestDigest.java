package io.github.temporalrift.workbench.experiment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical manifest digest. Object keys are sorted recursively; array order is significant because
 * the matrix preview enumerates variants, policies, and seat assignments in manifest order. Only
 * semantically order-insensitive collections (seeds, player counts, faction sets) are normalized
 * before hashing, so the digest never changes with whitespace or key order.
 */
public final class ManifestDigest {

    private ManifestDigest() {}

    public static String sha256Hex(JsonNode manifest) {
        var canonical = canonicalize(manifest);
        var bytes = canonical.getBytes(StandardCharsets.UTF_8);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException _) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static String canonicalize(JsonNode manifest) {
        return canonicalNode(manifest, true).toString();
    }

    private static JsonNode canonicalNode(JsonNode node, boolean root) {
        if (node instanceof ObjectNode object) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (var entry : object.properties()) {
                sorted.put(entry.getKey(), canonicalNode(entry.getValue(), false));
            }
            var result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            if (root) {
                normalizeSets(result);
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            var result = JsonNodeFactory.instance.arrayNode();
            array.forEach(item -> result.add(canonicalNode(item, false)));
            return result;
        }
        return node;
    }

    private static void normalizeSets(ObjectNode root) {
        sortStringArray(root, "seeds", Comparator.comparingLong(ManifestDigest::parseUint64));
        sortIntArray(root, "playerCounts");
        sortFactionSets(root);
    }

    private static void sortStringArray(ObjectNode root, String field, Comparator<String> order) {
        var array = root.get(field);
        if (!(array instanceof ArrayNode list)) {
            return;
        }
        List<String> values = new ArrayList<>();
        list.forEach(item -> values.add(item.asString()));
        values.sort(order);
        var sorted = JsonNodeFactory.instance.arrayNode();
        values.forEach(sorted::add);
        root.set(field, sorted);
    }

    private static void sortIntArray(ObjectNode root, String field) {
        var array = root.get(field);
        if (!(array instanceof ArrayNode list)) {
            return;
        }
        List<Long> values = new ArrayList<>();
        list.forEach(item -> values.add(item.asLong()));
        values.sort(Long::compareTo);
        var sorted = JsonNodeFactory.instance.arrayNode();
        values.forEach(sorted::add);
        root.set(field, sorted);
    }

    private static void sortFactionSets(ObjectNode root) {
        var array = root.get("factionSets");
        if (!(array instanceof ArrayNode sets)) {
            return;
        }
        List<String> normalized = new ArrayList<>();
        sets.forEach(set -> {
            List<String> factions = new ArrayList<>();
            set.forEach(faction -> factions.add(faction.asString()));
            factions.sort(String::compareTo);
            normalized.add(String.join(",", factions));
        });
        normalized.sort(String::compareTo);
        var sorted = JsonNodeFactory.instance.arrayNode();
        normalized.forEach(entry -> {
            var set = JsonNodeFactory.instance.arrayNode();
            List.of(entry.split(",")).forEach(set::add);
            sorted.add(set);
        });
        root.set("factionSets", sorted);
    }

    private static long parseUint64(String value) {
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
