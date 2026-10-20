package io.github.temporalrift.workbench.experiment.domain.rules;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One immutable version of the rules a variant may override: the parameters with their ranges and steps, the values
 * derived from them, the constraints the whole rules must satisfy, and the properties held fixed.
 */
public record ParameterSpace(
        int version,
        List<Parameter> parameters,
        List<Derived> derived,
        List<Constraint> constraints,
        List<HeldFixed> heldFixed) {

    public ParameterSpace {
        if (version < 1) {
            throw new IllegalArgumentException("a parameter space version must be at least 1: " + version);
        }
        parameters = List.copyOf(parameters);
        derived = List.copyOf(derived);
        constraints = constraints.stream()
                .sorted(Comparator.comparing(Constraint::id))
                .toList();
        heldFixed = List.copyOf(heldFixed);
        var keys = new HashSet<String>();
        for (var parameter : parameters) {
            requireUnique(keys, parameter.key());
        }
        var parameterKeys = Set.copyOf(keys);
        for (var value : derived) {
            requireUnique(keys, value.key());
            if (!parameterKeys.contains(value.from())) {
                throw new IllegalArgumentException(
                        value.key() + " derives from " + value.from() + ", which is not a parameter");
            }
        }
        var ids = new HashSet<String>();
        for (var constraint : constraints) {
            requireUnique(ids, constraint.id());
        }
    }

    private static void requireUnique(HashSet<String> seen, String name) {
        if (!seen.add(name)) {
            throw new IllegalArgumentException("a parameter space names " + name + " twice");
        }
    }

    public Optional<Parameter> parameter(String key) {
        return parameters.stream()
                .filter(parameter -> parameter.key().equals(key))
                .findFirst();
    }

    /**
     * A property override may set.
     *
     * @param services the services that read it
     * @param baseline the value the shared configuration serves
     * @param step the grid the values lie on, starting from {@code min}
     */
    public record Parameter(
            String key, List<String> services, BigDecimal baseline, BigDecimal min, BigDecimal max, BigDecimal step) {

        public Parameter {
            Objects.requireNonNull(key, "key");
            services = List.copyOf(services);
            if (services.isEmpty()) {
                throw new IllegalArgumentException(key + " is read by no service");
            }
            if (step.signum() <= 0 || min.compareTo(max) > 0) {
                throw new IllegalArgumentException(key + " has an empty range or a non-positive step");
            }
            if (!onGrid(baseline, min, max, step)) {
                throw new IllegalArgumentException(key + " has a baseline outside its range or off its step");
            }
        }

        /** Whether the value lies in {@code [min, max]} and on the step grid. */
        private static boolean onGrid(BigDecimal value, BigDecimal min, BigDecimal max, BigDecimal step) {
            return value.compareTo(min) >= 0
                    && value.compareTo(max) <= 0
                    && value.subtract(min).remainder(step).signum() == 0;
        }

        public boolean inRange(BigDecimal value) {
            return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
        }

        public boolean onStep(BigDecimal value) {
            return value.subtract(min).remainder(step).signum() == 0;
        }
    }

    /** A value that always equals {@code factor} times the parameter {@code from}. */
    public record Derived(String key, String from, BigDecimal factor) {}

    /** A condition on the whole effective rules, in the notation of {@link ConstraintExpression}. */
    public record Constraint(String id, ConstraintExpression expression, String description) {

        public static Constraint of(String id, String expression, String description) {
            return new Constraint(id, ConstraintExpression.parse(expression), description);
        }
    }

    /** A property no variant may change, and why. */
    public record HeldFixed(String key, String reason) {}
}
