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
 * Canonical manifest digest. Object keys are sorted recursively and semantically order-insensitive
 * collections (seeds, player counts, faction sets, variants, policies, contracts) are normalized
 * before hashing, so the digest changes only when the experiment's meaning changes — never with
 * whitespace or key order.
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
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
            List<JsonNode> items = new ArrayList<>();
            array.forEach(item -> items.add(canonicalNode(item, false)));
            items.sort(Comparator.comparing(JsonNode::toString));
            var result = JsonNodeFactory.instance.arrayNode();
            items.forEach(result::add);
            return result;
        }
        return node;
    }

    private static void normalizeSets(ObjectNode root) {
        sortStringArray(root, "seeds", Comparator.comparingLong(ManifestDigest::parseUint64));
        sortIntArray(root, "playerCounts");
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

    private static long parseUint64(String value) {
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
