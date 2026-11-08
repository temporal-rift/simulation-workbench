package io.github.temporalrift.workbench.analysis.domain.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ClopperPearsonTest {

    @Test
    void noEventInTenTrialsHasAnUpperBoundOfAboutThirtyOnePercent() {
        var bounds = ClopperPearson.interval(0, 10, 0.95);

        assertThat(bounds[0]).isZero();
        assertThat(bounds[1])
                .isCloseTo(1 - Math.pow(0.025, 1.0 / 10), within(1e-12))
                .isCloseTo(0.3085, within(5e-5));
    }

    @Test
    void noEventInFiveHundredTwentyTrialsHasAnUpperBoundBelowOnePercent() {
        var bounds = ClopperPearson.interval(0, 520, 0.95);

        assertThat(bounds[1])
                .isCloseTo(1 - Math.pow(0.025, 1.0 / 520), within(1e-12))
                .isCloseTo(0.0071, within(5e-5));
    }

    @Test
    void onlySuccessesHaveAnUpperBoundOfOne() {
        var bounds = ClopperPearson.interval(10, 10, 0.95);

        assertThat(bounds[0]).isCloseTo(Math.pow(0.025, 1.0 / 10), within(1e-12));
        assertThat(bounds[1]).isEqualTo(1.0);
    }

    @Test
    void anInteriorShareMatchesTheBetaQuantiles() {
        // Reference values: the 0.025 quantile of Beta(3, 18) and the 0.975 quantile of Beta(4, 17).
        var bounds = ClopperPearson.interval(3, 20, 0.95);

        assertThat(bounds[0]).isCloseTo(0.0320709, within(1e-6));
        assertThat(bounds[1]).isCloseTo(0.3789268, within(1e-6));
    }

    @Test
    void aBillionTrialsGiveOrderedBoundsAroundTheShare() {
        // At this size the interval matches the normal approximation 0.5 ± 1.96 √(0.25 / 10⁹).
        var halfWidth = 1.959964 * Math.sqrt(0.25 / 1e9);

        var bounds = ClopperPearson.interval(500_000_000, 1_000_000_000, 0.95);

        assertThat(bounds[0]).isCloseTo(0.5 - halfWidth, within(1e-7));
        assertThat(bounds[1]).isCloseTo(0.5 + halfWidth, within(1e-7));
    }

    @Test
    void theIncompleteBetaIsTheBinomialTail() {
        // I_p(k, n - k + 1) = P(X >= k) for X ~ Binomial(n, p); here n = 5, k = 2, p = 0.3.
        var tail = 1 - Math.pow(0.7, 5) - 5 * 0.3 * Math.pow(0.7, 4);

        assertThat(ClopperPearson.regularizedBeta(0.3, 2, 4)).isCloseTo(tail, within(1e-12));
    }

    @Test
    void countsWithoutTrialsHaveNoInterval() {
        assertThatThrownBy(() -> ClopperPearson.interval(0, 0, 0.95)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClopperPearson.interval(3, 2, 0.95)).isInstanceOf(IllegalArgumentException.class);
    }
}
