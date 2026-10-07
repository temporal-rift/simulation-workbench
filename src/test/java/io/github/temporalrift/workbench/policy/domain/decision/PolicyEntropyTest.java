package io.github.temporalrift.workbench.policy.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class PolicyEntropyTest {

    private static int[] draw(PolicyEntropy entropy) {
        return IntStream.range(0, 8).map(i -> entropy.nextIndex(1_000_000)).toArray();
    }

    @Test
    void equalInputsGiveEqualStreams() {
        assertThat(draw(PolicyEntropy.derive(42, 1, "era1/round1/action", 0)))
                .containsExactly(draw(PolicyEntropy.derive(42, 1, "era1/round1/action", 0)));
    }

    @Test
    void seedSeatWindowAndRejectionCountEachSeparateStreams() {
        var base = draw(PolicyEntropy.derive(42, 1, "w", 0));

        assertThat(draw(PolicyEntropy.derive(43, 1, "w", 0))).isNotEqualTo(base);
        assertThat(draw(PolicyEntropy.derive(42, 2, "w", 0))).isNotEqualTo(base);
        assertThat(draw(PolicyEntropy.derive(42, 1, "x", 0))).isNotEqualTo(base);
        assertThat(draw(PolicyEntropy.derive(42, 1, "w", 1))).isNotEqualTo(base);
    }
}
