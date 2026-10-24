package io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Writes and reads the JSON text columns of the workbench tables with the application's configured mapper. */
@Component
public class StoredJson {

    private final ObjectMapper objectMapper;

    public StoredJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored " + type.getSimpleName() + " is not valid JSON", e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored JSON column is not valid", e);
        }
    }
}
