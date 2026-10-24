package io.github.temporalrift.workbench.policy.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

class PolicyEntropyTest {

    private static final WindowRef ROUND_ONE = WindowRef.actionRound(1, 1);
    private static final WindowRef WINDOW = WindowRef.of(WindowKind.DECLARATION, 1);

    private static int[] draw(PolicyEntropy entropy) {
        return IntStream.range(0, 8).map(i -> entropy.nextIndex(1_000_000)).toArray();
    }

    @Test
    void equalInputsGiveEqualStreams() {
        assertThat(draw(PolicyEntropy.derive(42, 1, ROUND_ONE, 0)))
                .containsExactly(draw(PolicyEntropy.derive(42, 1, ROUND_ONE, 0)));
    }

    @Test
    void seedSeatWindowAndRejectionCountEachSeparateStreams() {
        var base = draw(PolicyEntropy.derive(42, 1, WINDOW, 0));

        assertThat(draw(PolicyEntropy.derive(43, 1, WINDOW, 0))).isNotEqualTo(base);
        assertThat(draw(PolicyEntropy.derive(42, 2, WINDOW, 0))).isNotEqualTo(base);
        assertThat(draw(PolicyEntropy.derive(42, 1, WindowRef.actionRound(1, 2), 0)))
                .isNotEqualTo(base);
        assertThat(draw(PolicyEntropy.derive(42, 1, WINDOW, 1))).isNotEqualTo(base);
    }
}
