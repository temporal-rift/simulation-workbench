package io.github.temporalrift.workbench.shared.text;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON text with one canonical mapper, so every digest and every comparison of JSON text sees the same bytes.
 * Failures are wrapped in an {@link IllegalStateException} naming what the caller was handling.
 */
public final class JsonText {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private JsonText() {}

    /** The compact text of the value with every object's keys sorted recursively; array order is kept. */
    public static String canonical(Object value, String description) {
        try {
            return MAPPER.writeValueAsString(MAPPER.treeToValue(MAPPER.valueToTree(value), Object.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize " + description, e);
        }
    }

    /** The value as a tree, for a caller that amends it before rendering it with {@link #canonical}. */
    public static JsonNode tree(Object value, String description) {
        try {
            return MAPPER.valueToTree(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize " + description, e);
        }
    }

    public static String write(Object value, String description) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize " + description, e);
        }
    }

    public static JsonNode readTree(String json, String description) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException(description + " cannot be read as JSON", e);
        }
    }

    public static <T> T read(String json, Class<T> type, String description) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException(description + " cannot be read as JSON", e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type, String description) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException(description + " cannot be read as JSON", e);
        }
    }
}
