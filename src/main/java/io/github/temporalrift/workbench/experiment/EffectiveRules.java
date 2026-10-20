package io.github.temporalrift.workbench.experiment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A complete, materialised rules document: every {@code game.rules.*} property keyed by its full path. Its digest is
 * the SHA-256 of the RFC 8785 serialisation of the properties, so neither key order nor number spelling changes it.
 */
public record EffectiveRules(SortedMap<String, JsonNode> properties) {

    public static final int SCHEMA_VERSION = 1;
    private static final String KEY_PREFIX = "game.rules.";

    public EffectiveRules {
        properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
    }

    /** Reads a document of the published {@code EffectiveRules} shape, refusing one that is not. */
    public static EffectiveRules parse(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("a rules document must be a JSON object");
        }
        if (document.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("a rules document's schemaVersion must be " + SCHEMA_VERSION);
        }
        var node = document.get("properties");
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("a rules document's properties must be a JSON object");
        }
        var properties = new TreeMap<String, JsonNode>();
        for (var entry : node.properties()) {
            if (!entry.getKey().startsWith(KEY_PREFIX)) {
                throw new IllegalArgumentException(
                        "a rules property must start with " + KEY_PREFIX + ": " + entry.getKey());
            }
            if (!isValue(entry.getValue())) {
                throw new IllegalArgumentException(
                        "a rules property must be a number, boolean, string, or array of those: " + entry.getKey());
            }
            properties.put(entry.getKey(), entry.getValue());
        }
        return new EffectiveRules(properties);
    }

    private static boolean isValue(JsonNode value) {
        if (value.isArray()) {
            return value.valueStream().allMatch(EffectiveRules::isScalar);
        }
        return isScalar(value);
    }

    private static boolean isScalar(JsonNode value) {
        return value.isNumber() || value.isBoolean() || value.isTextual();
    }

    /** The lowercase hex SHA-256 of the canonical serialisation of the properties. */
    public String digest() {
        try {
            var canonical = RulesCanonicalJson.write(properties).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The numeric value of a property, or empty when it is absent or not a number. */
    public Optional<BigDecimal> number(String key) {
        return Optional.ofNullable(properties.get(key))
                .filter(JsonNode::isNumber)
                .map(JsonNode::decimalValue);
    }

    /** This document with the given properties replaced or added. */
    public EffectiveRules with(SortedMap<String, JsonNode> changes) {
        var merged = new TreeMap<>(properties);
        merged.putAll(changes);
        return new EffectiveRules(merged);
    }

    /** The document in its published shape. */
    public JsonNode document() {
        var document = JsonNodeFactory.instance.objectNode();
        document.put("schemaVersion", SCHEMA_VERSION);
        var node = document.putObject("properties");
        properties.forEach(node::set);
        return document;
    }
}
