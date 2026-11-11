package io.github.temporalrift.workbench.search.domain.candidate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/**
 * The discrete space a search moves in: every overridable parameter of a parameter space in key order, each with the
 * values in order. A point of the space is one grid index per parameter.
 */
public record ParameterGrid(List<Axis> axes) {

    public ParameterGrid {
        axes = List.copyOf(axes);
        if (axes.isEmpty()) {
            throw new IllegalArgumentException("a parameter grid needs at least one parameter");
        }
        for (var index = 1; index < axes.size(); index++) {
            if (axes.get(index - 1).key().compareTo(axes.get(index).key()) >= 0) {
                throw new IllegalArgumentException("axes must be in ascending key order without repeats");
            }
        }
    }

    /** One parameter: its values in order, indexed from 0, and the one the baseline rules carry. */
    public sealed interface Axis permits NumberAxis, FlagAxis {

        String key();

        /** The highest value index; the indices run from 0. */
        int highestIndex();

        int baselineIndex();

        ParameterValue valueAt(int index);
    }

    /** A numeric parameter: the values {@code min}, {@code min + step}, ... up to {@code max}. */
    public record NumberAxis(String key, BigDecimal min, BigDecimal max, BigDecimal step, BigDecimal baseline)
            implements Axis {

        public NumberAxis {
            if (step.signum() <= 0 || min.compareTo(max) > 0) {
                throw new IllegalArgumentException(key + " has an empty range or a non-positive step");
            }
            var offset = baseline.subtract(min);
            if (offset.signum() < 0
                    || baseline.compareTo(max) > 0
                    || offset.remainder(step).signum() != 0) {
                throw new IllegalArgumentException(key + " has a baseline outside its range or off its step");
            }
        }

        @Override
        public int highestIndex() {
            return max.subtract(min).divideToIntegralValue(step).intValueExact();
        }

        @Override
        public int baselineIndex() {
            return baseline.subtract(min)
                    .divide(step, 0, RoundingMode.UNNECESSARY)
                    .intValueExact();
        }

        @Override
        public ParameterValue valueAt(int index) {
            if (index < 0 || index > highestIndex()) {
                throw new IllegalArgumentException(key + " has no grid index " + index);
            }
            return ParameterValue.of(min.add(step.multiply(BigDecimal.valueOf(index))));
        }
    }

    /** A boolean parameter: index 0 is {@code false} and index 1 is {@code true}. */
    public record FlagAxis(String key, boolean baseline) implements Axis {

        @Override
        public int highestIndex() {
            return 1;
        }

        @Override
        public int baselineIndex() {
            return baseline ? 1 : 0;
        }

        @Override
        public ParameterValue valueAt(int index) {
            if (index < 0 || index > 1) {
                throw new IllegalArgumentException(key + " has no grid index " + index);
            }
            return ParameterValue.of(index == 1);
        }
    }

    /** The point of the baseline itself. */
    public List<Integer> baselinePoint() {
        return axes.stream().map(Axis::baselineIndex).toList();
    }

    /** The overrides a point stands for: the parameters that differ from the baseline, by key. */
    public SortedMap<String, ParameterValue> overridesOf(List<Integer> point) {
        if (point.size() != axes.size()) {
            throw new IllegalArgumentException("a point needs one index per parameter");
        }
        var overrides = new TreeMap<String, ParameterValue>();
        for (var index = 0; index < axes.size(); index++) {
            var axis = axes.get(index);
            if (point.get(index) != axis.baselineIndex()) {
                overrides.put(axis.key(), axis.valueAt(point.get(index)));
            }
        }
        return overrides;
    }
}
