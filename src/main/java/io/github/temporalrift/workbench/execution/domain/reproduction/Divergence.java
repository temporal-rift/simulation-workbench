package io.github.temporalrift.workbench.execution.domain.reproduction;

import java.util.Map;
import java.util.Objects;

/**
 * The first place a reproduction differed from the saved case.
 *
 * @param step position in the saved case's evidence stream; an ending difference sits after the last step
 * @param kind what differed, for example {@code OBSERVATION} or {@code FINAL_SCORES}
 */
public record Divergence(int step, String kind, Map<String, Object> expected, Map<String, Object> actual) {

    public Divergence {
        Objects.requireNonNull(kind, "kind");
        expected = Map.copyOf(expected);
        actual = Map.copyOf(actual);
    }
}
