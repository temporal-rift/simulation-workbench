package io.github.temporalrift.workbench.search.domain.candidate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.TreeMap;

/**
 * The discrete space a search moves in: every overridable parameter of a parameter space in key order, each with the
 * values on its step grid. A point of the space is one grid index per parameter.
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

    /**
     * One parameter: the values {@code min}, {@code min + step}, ... up to {@code max}, and the one the baseline
     * rules carry.
     */
    public record Axis(String key, BigDecimal min, BigDecimal max, BigDecimal step, BigDecimal baseline) {

        public Axis {
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

        /** The highest grid index; the indices run from 0. */
        public int highestIndex() {
            return max.subtract(min).divideToIntegralValue(step).intValueExact();
        }

        public int baselineIndex() {
            return baseline.subtract(min)
                    .divide(step, 0, RoundingMode.UNNECESSARY)
                    .intValueExact();
        }

        public BigDecimal valueAt(int index) {
            if (index < 0 || index > highestIndex()) {
                throw new IllegalArgumentException(key + " has no grid index " + index);
            }
            return min.add(step.multiply(BigDecimal.valueOf(index)));
        }
    }

    /** The point of the baseline itself. */
    public List<Integer> baselinePoint() {
        return axes.stream().map(Axis::baselineIndex).toList();
    }

    /** The overrides a point stands for: the parameters that differ from the baseline, by key. */
    public TreeMap<String, BigDecimal> overridesOf(List<Integer> point) {
        if (point.size() != axes.size()) {
            throw new IllegalArgumentException("a point needs one index per parameter");
        }
        var overrides = new TreeMap<String, BigDecimal>();
        for (var index = 0; index < axes.size(); index++) {
            var axis = axes.get(index);
            if (point.get(index) != axis.baselineIndex()) {
                overrides.put(axis.key(), axis.valueAt(point.get(index)));
            }
        }
        return overrides;
    }
}
