package io.github.temporalrift.workbench.experiment.domain.rules;

import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Constraint;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/**
 * Turns a baseline rules document, a parameter space and overrides into the variant's effective rules. It is a pure
 * function of those three inputs: the same inputs always give byte-identical rules and the same digest.
 */
public final class VariantMaterializer {

    private static final String NUMBER = "number";
    private static final String BOOLEAN = "boolean";

    private VariantMaterializer() {}

    /** The effective rules of a variant and the overrides that remain once those equal to the baseline are dropped. */
    public record Materialized(EffectiveRules rules, SortedMap<String, ParameterValue> overrides) {

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
            EffectiveRules baseline, ParameterSpace space, Map<String, ParameterValue> overrides) {
        if (overrides.isEmpty()) {
            return new Materialized(baseline, new TreeMap<>());
        }
        requireBaselineCarries(baseline, space);
        var sorted = new TreeMap<>(overrides);
        sorted.forEach((key, value) -> requireAllowed(space, key, value));
        var changed = new TreeMap<String, ParameterValue>();
        sorted.forEach((key, value) -> {
            if (!value.equals(baseline.value(key).orElseThrow())) {
                changed.put(key, value);
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
        var required = new TreeMap<String, String>();
        space.parameters()
                .forEach(parameter -> required.put(
                        parameter.key(), parameter instanceof ParameterSpace.BooleanParameter ? BOOLEAN : NUMBER));
        space.constraints()
                .forEach(
                        constraint -> constraint.expression().keys().forEach(key -> required.putIfAbsent(key, NUMBER)));
        required.entrySet().stream()
                .filter(entry -> !carries(baseline, entry.getKey(), entry.getValue()))
                .findFirst()
                .ifPresent(entry -> {
                    throw new VariantRulesRefusal(
                            VariantRulesReason.UNKNOWN_BASELINE,
                            "the baseline rules carry no " + entry.getValue() + " at " + entry.getKey()
                                    + ", which parameter space " + space.version() + " needs",
                            null,
                            null);
                });
    }

    private static boolean carries(EffectiveRules baseline, String key, String type) {
        return BOOLEAN.equals(type)
                ? baseline.flag(key).isPresent()
                : baseline.number(key).isPresent();
    }

    private static void requireAllowed(ParameterSpace space, String key, ParameterValue value) {
        var parameter = space.parameter(key)
                .orElseThrow(() -> new VariantRulesRefusal(
                        VariantRulesReason.UNKNOWN_PARAMETER,
                        key + " is not a parameter of parameter space " + space.version(),
                        key,
                        null));
        switch (parameter) {
            case ParameterSpace.NumberParameter number -> requireNumber(number, value);
            case ParameterSpace.BooleanParameter flag -> requireFlag(flag, value);
        }
    }

    private static void requireFlag(ParameterSpace.BooleanParameter parameter, ParameterValue value) {
        if (!(value instanceof ParameterValue.Flag)) {
            throw wrongType(parameter.key(), "a boolean", value);
        }
    }

    private static void requireNumber(ParameterSpace.NumberParameter parameter, ParameterValue value) {
        var key = parameter.key();
        if (!(value instanceof ParameterValue.Decimal(var number))) {
            throw wrongType(key, "a number", value);
        }
        if (!parameter.inRange(number)) {
            throw new VariantRulesRefusal(
                    VariantRulesReason.OUT_OF_RANGE,
                    key + " must be from " + parameter.min().toPlainString() + " to "
                            + parameter.max().toPlainString() + ": " + number.toPlainString(),
                    key,
                    null);
        }
        if (!parameter.onStep(number)) {
            throw new VariantRulesRefusal(
                    VariantRulesReason.OFF_STEP,
                    key + " must lie on a step of " + parameter.step().toPlainString() + " from "
                            + parameter.min().toPlainString() + ": " + number.toPlainString(),
                    key,
                    null);
        }
    }

    private static VariantRulesRefusal wrongType(String key, String expected, ParameterValue value) {
        return new VariantRulesRefusal(
                VariantRulesReason.WRONG_TYPE,
                key + " must be " + expected + ": its parameter is "
                        + (expected.equals("a number") ? "numeric" : BOOLEAN) + ", not " + value.text(),
                key,
                null);
    }

    /** The overrides as rules properties, followed by the derived values of every overridden source. */
    private static SortedMap<String, JsonNode> applied(
            ParameterSpace space, SortedMap<String, ParameterValue> changed) {
        var properties = new TreeMap<String, JsonNode>();
        changed.forEach((key, value) -> properties.put(key, value.json()));
        space.derived().stream()
                .filter(derived -> changed.containsKey(derived.from()))
                .forEach(derived -> {
                    var source = (ParameterValue.Decimal) changed.get(derived.from());
                    properties.put(
                            derived.key(),
                            ParameterValue.of(source.value().multiply(derived.factor()))
                                    .json());
                });
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
}
