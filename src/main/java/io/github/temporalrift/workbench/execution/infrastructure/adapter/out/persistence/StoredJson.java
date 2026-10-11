package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Writes and reads the JSON text columns of the execution tables. */
@Component
class StoredJson {

    private final ObjectMapper objectMapper;

    StoredJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored " + type.getSimpleName() + " is not valid JSON", e);
        }
    }

    <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored JSON column is not valid", e);
        }
    }
}
