package io.github.temporalrift.workbench.experiment.domain.rules;

import java.math.BigDecimal;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Constraint;

/**
 * Turns a baseline rules document, a parameter space and overrides into the variant's effective rules. It is a pure
 * function of those three inputs: the same inputs always give byte-identical rules and the same digest.
 */
public final class VariantMaterializer {

    private VariantMaterializer() {}

    /** The effective rules of a variant and the overrides that remain once those equal to the baseline are dropped. */
    public record Materialized(EffectiveRules rules, SortedMap<String, BigDecimal> overrides) {

        public String digest() {
            return rules.digest();
        }
    }

    /**
     * Validates the overrides against the space, drops those equal to the baseline, applies the rest and the values
     * derived from them, and checks every constraint on the result. The first refusal is by key in ascending order,
     * then by constraint id.
     *
     * @throws VariantRulesRefusal when the baseline cannot carry the space or an override or constraint is refused
     */
    public static Materialized materialize(
            EffectiveRules baseline, ParameterSpace space, Map<String, BigDecimal> overrides) {
        if (overrides.isEmpty()) {
            return new Materialized(baseline, new TreeMap<>());
        }
        requireBaselineCarries(baseline, space);
        var sorted = new TreeMap<>(overrides);
        sorted.forEach((key, value) -> requireAllowed(space, key, value));
        var changed = new TreeMap<String, BigDecimal>();
        sorted.forEach((key, value) -> {
            if (value.compareTo(baseline.number(key).orElseThrow()) != 0) {
                changed.put(key, normalized(value));
            }
        });
        if (changed.isEmpty()) {
            return new Materialized(baseline, changed);
        }
        var rules = baseline.with(applied(space, changed));
        space.constraints().stream()
                .filter(constraint -> !constraint.expression().holds(rules))
                .findFirst()
                .ifPresent(constraint -> {
                    throw violated(space, constraint, changed.navigableKeySet());
                });
        return new Materialized(rules, changed);
    }

    private static void requireBaselineCarries(EffectiveRules baseline, ParameterSpace space) {
        var required = new TreeSet<String>();
        space.parameters().forEach(parameter -> required.add(parameter.key()));
        space.constraints()
                .forEach(constraint -> required.addAll(constraint.expression().keys()));
        required.stream()
                .filter(key -> baseline.number(key).isEmpty())
                .findFirst()
                .ifPresent(key -> {
                    throw new VariantRulesRefusal(
                            VariantRulesReason.UNKNOWN_BASELINE,
                            "the baseline rules carry no number at " + key + ", which parameter space "
                                    + space.version() + " needs",
                            null,
                            null);
                });
    }

    private static void requireAllowed(ParameterSpace space, String key, BigDecimal value) {
        var parameter = space.parameter(key)
                .orElseThrow(() -> new VariantRulesRefusal(
                        VariantRulesReason.UNKNOWN_PARAMETER,
                        key + " is not a parameter of parameter space " + space.version(),
                        key,
                        null));
        if (!parameter.inRange(value)) {
            throw new VariantRulesRefusal(
                    VariantRulesReason.OUT_OF_RANGE,
                    key + " must be from " + parameter.min().toPlainString() + " to "
                            + parameter.max().toPlainString() + ": " + value.toPlainString(),
                    key,
                    null);
        }
        if (!parameter.onStep(value)) {
            throw new VariantRulesRefusal(
                    VariantRulesReason.OFF_STEP,
                    key + " must lie on a step of " + parameter.step().toPlainString() + " from "
                            + parameter.min().toPlainString() + ": " + value.toPlainString(),
                    key,
                    null);
        }
    }

    /** The overrides as rules properties, followed by the derived values of every overridden source. */
    private static SortedMap<String, JsonNode> applied(ParameterSpace space, SortedMap<String, BigDecimal> changed) {
        var properties = new TreeMap<String, JsonNode>();
        changed.forEach((key, value) -> properties.put(key, number(value)));
        space.derived().stream()
                .filter(derived -> changed.containsKey(derived.from()))
                .forEach(derived -> properties.put(
                        derived.key(),
                        number(normalized(changed.get(derived.from()).multiply(derived.factor())))));
        return properties;
    }

    private static VariantRulesRefusal violated(
            ParameterSpace space, Constraint constraint, SortedSet<String> overriddenKeys) {
        var read = new TreeSet<String>();
        constraint.expression().keys().forEach(key -> read.add(sourceOf(space, key)));
        var culprit = overriddenKeys.stream().filter(read::contains).findFirst().orElse(null);
        return new VariantRulesRefusal(
                VariantRulesReason.CONSTRAINT_VIOLATED,
                "constraint " + constraint.id() + " is violated: " + constraint.description(),
                culprit,
                constraint.id());
    }

    private static String sourceOf(ParameterSpace space, String key) {
        return space.derived().stream()
                .filter(derived -> derived.key().equals(key))
                .map(ParameterSpace.Derived::from)
                .findFirst()
                .orElse(key);
    }

    /** The value without trailing zeros, so that {@code 25}, {@code 25.0} and {@code 2.5E+1} are one value. */
    public static BigDecimal normalized(BigDecimal value) {
        var stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** A rules number: an integer when the value is whole, a decimal otherwise. */
    public static JsonNode number(BigDecimal value) {
        var normalized = normalized(value);
        return normalized.scale() == 0 && normalized.precision() <= 18
                ? JsonNodeFactory.instance.numberNode(normalized.longValueExact())
                : JsonNodeFactory.instance.numberNode(normalized);
    }
}
