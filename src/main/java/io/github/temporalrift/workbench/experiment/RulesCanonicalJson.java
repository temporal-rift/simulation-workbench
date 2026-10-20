package io.github.temporalrift.workbench.experiment;

import java.math.BigDecimal;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * The RFC 8785 (JSON Canonicalization Scheme) serialisation of a flat property map: keys in UTF-16 code unit order, no
 * whitespace, numbers as ECMAScript prints them.
 */
final class RulesCanonicalJson {

    private static final int MAX_PLAIN_EXPONENT = 21;
    private static final int MIN_PLAIN_EXPONENT = -6;

    private RulesCanonicalJson() {}

    static String write(Map<String, JsonNode> sortedProperties) {
        var out = new StringBuilder("{");
        var first = true;
        for (var entry : sortedProperties.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, entry.getKey());
            out.append(':');
            value(out, entry.getValue());
        }
        return out.append('}').toString();
    }

    private static void value(StringBuilder out, JsonNode value) {
        if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                scalar(out, value.get(i));
            }
            out.append(']');
        } else {
            scalar(out, value);
        }
    }

    private static void scalar(StringBuilder out, JsonNode value) {
        if (value.isNumber()) {
            out.append(number(value.doubleValue()));
        } else if (value.isBoolean()) {
            out.append(value.booleanValue());
        } else {
            string(out, value.asString());
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** ECMAScript {@code Number::toString}: the shortest digits that round-trip, laid out by magnitude. */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("a rules number must be finite");
        }
        if (value == 0) {
            return "0";
        }
        var sign = value < 0 ? "-" : "";
        var shortest = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
        var digits = shortest.unscaledValue().toString();
        var k = digits.length();
        var n = k - shortest.scale();
        if (k <= n && n <= MAX_PLAIN_EXPONENT) {
            return sign + digits + "0".repeat(n - k);
        }
        if (0 < n && n <= MAX_PLAIN_EXPONENT) {
            return sign + digits.substring(0, n) + "." + digits.substring(n);
        }
        if (MIN_PLAIN_EXPONENT < n && n <= 0) {
            return sign + "0." + "0".repeat(-n) + digits;
        }
        var exponent = n - 1;
        var mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }
}
