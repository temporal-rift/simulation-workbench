package io.github.temporalrift.workbench.execution;

import java.util.Map;

/** The published shape of the first place a reproduction differed from the saved case. */
public record DivergenceReport(int step, String kind, Map<String, Object> expected, Map<String, Object> actual) {

    public DivergenceReport {
        expected = Map.copyOf(expected);
        actual = Map.copyOf(actual);
    }
}
