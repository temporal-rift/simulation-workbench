package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Map;

/** Where the real lane first differed from the simulator, in the published shape of a divergence. */
public record FirstDivergence(int step, String kind, Map<String, Object> expected, Map<String, Object> actual) {

    public FirstDivergence {
        expected = Map.copyOf(expected);
        actual = Map.copyOf(actual);
    }
}
