package io.github.temporalrift.workbench.fidelity.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The two variants at opposite edges of a parameter space, each as overrides on the baseline: {@code low} holds as many
 * parameters as the constraints allow at their range minimum, {@code high} at their range maximum.
 */
public record EdgeVariants(
        int parameterSpaceVersion, SortedMap<String, BigDecimal> low, SortedMap<String, BigDecimal> high) {

    public EdgeVariants {
        if (parameterSpaceVersion < 1) {
            throw new IllegalArgumentException("parameterSpaceVersion must be at least 1: " + parameterSpaceVersion);
        }
        low = Collections.unmodifiableSortedMap(new TreeMap<>(low));
        high = Collections.unmodifiableSortedMap(new TreeMap<>(high));
    }
}
