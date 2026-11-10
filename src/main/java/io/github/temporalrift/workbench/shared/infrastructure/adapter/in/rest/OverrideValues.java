package io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.OverrideValue;

/**
 * The value a variant sets for a parameter, as the contract's number-or-boolean union. The generated
 * {@link OverrideValue} names neither, so a value travels as the JSON it is; whether its type fits the parameter is
 * the domain's decision.
 */
public final class OverrideValues {

    private OverrideValues() {}

    /** One override value as the JSON number or boolean it is. */
    public record Value(@JsonValue JsonNode json) implements OverrideValue {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static Value read(JsonNode json) {
            return new Value(json);
        }
    }

    /** Numeric overrides as contract values, in the order given. */
    public static Map<String, OverrideValue> of(Map<String, BigDecimal> overrides) {
        var values = new LinkedHashMap<String, OverrideValue>();
        overrides.forEach((key, value) -> values.put(key, new Value(JsonNodeFactory.instance.numberNode(value))));
        return values;
    }

    /** Contract overrides as the JSON object a manifest carries; empty when there are none. */
    public static ObjectNode json(Map<String, OverrideValue> overrides) {
        var node = JsonNodeFactory.instance.objectNode();
        if (overrides != null) {
            overrides.forEach((key, value) -> node.set(key, ((Value) value).json()));
        }
        return node;
    }
}
