package io.github.temporalrift.workbench.shared.rules;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The value of one overridable rule property: a number for a numeric parameter, a flag for a boolean one. Numbers
 * compare by value, so {@code 25} and {@code 25.0} are one value.
 */
public sealed interface ParameterValue {

    /** A numeric value, without trailing zeros. */
    record Decimal(BigDecimal value) implements ParameterValue {

        public Decimal {
            value = normalized(value);
        }

        @Override
        public String text() {
            return value.toPlainString();
        }

        @Override
        public String toString() {
            return text();
        }

        @Override
        public JsonNode json() {
            return value.scale() == 0 && value.precision() <= 18
                    ? JsonNodeFactory.instance.numberNode(value.longValueExact())
                    : JsonNodeFactory.instance.numberNode(value);
        }
    }

    /** A boolean value. */
    record Flag(boolean value) implements ParameterValue {

        @Override
        public String text() {
            return Boolean.toString(value);
        }

        @Override
        public String toString() {
            return text();
        }

        @Override
        public JsonNode json() {
            return JsonNodeFactory.instance.booleanNode(value);
        }
    }

    static ParameterValue of(BigDecimal value) {
        return new Decimal(value);
    }

    static ParameterValue of(boolean value) {
        return new Flag(value);
    }

    /**
     * The value a JSON number or boolean stands for.
     *
     * @throws IllegalArgumentException for any other JSON value
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static ParameterValue of(JsonNode json) {
        if (json.isBoolean()) {
            return new Flag(json.asBoolean());
        }
        if (json.isNumber()) {
            return new Decimal(json.decimalValue());
        }
        throw new IllegalArgumentException("a parameter value must be a number or a boolean: " + json);
    }

    /** The value {@link #text()} wrote. */
    static ParameterValue parse(String text) {
        return switch (text) {
            case "true" -> new Flag(true);
            case "false" -> new Flag(false);
            default -> new Decimal(new BigDecimal(text));
        };
    }

    /** The value as stored in text: a plain decimal, or {@code true} or {@code false}. */
    String text();

    /** The value as the JSON number or boolean a rules document and a manifest carry, and as it is serialised. */
    @JsonValue
    JsonNode json();

    /** The value without trailing zeros, so that {@code 25}, {@code 25.0} and {@code 2.5E+1} are one value. */
    static BigDecimal normalized(BigDecimal value) {
        var stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
