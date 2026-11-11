package io.github.temporalrift.workbench.search.domain.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.rules.ParameterValue;

class ParameterGridTest {

    private static final ParameterGrid GRID = new ParameterGrid(List.of(
            new ParameterGrid.NumberAxis(
                    "a.round", BigDecimal.ONE, BigDecimal.valueOf(3), BigDecimal.ONE, BigDecimal.valueOf(2)),
            new ParameterGrid.FlagAxis("b.on", true),
            new ParameterGrid.FlagAxis("c.off", false)));

    @Test
    void aBooleanAxisHasTwoValuesFalseThenTrue() {
        var axis = new ParameterGrid.FlagAxis("b.on", true);

        assertThat(axis.highestIndex()).isEqualTo(1);
        assertThat(axis.valueAt(0)).isEqualTo(ParameterValue.of(false));
        assertThat(axis.valueAt(1)).isEqualTo(ParameterValue.of(true));
        assertThatThrownBy(() -> axis.valueAt(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> axis.valueAt(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBooleanAxisBaselineIndexFollowsItsBaselineValue() {
        assertThat(new ParameterGrid.FlagAxis("x", true).baselineIndex()).isEqualTo(1);
        assertThat(new ParameterGrid.FlagAxis("x", false).baselineIndex()).isZero();
    }

    @Test
    void theBaselinePointOverridesNothing() {
        assertThat(GRID.baselinePoint()).containsExactly(1, 1, 0);
        assertThat(GRID.overridesOf(GRID.baselinePoint())).isEmpty();
    }

    @Test
    void aPointOverridesTheAxesThatLeaveTheirBaselineWithTypedValues() {
        var overrides = GRID.overridesOf(List.of(0, 0, 1));

        assertThat(overrides)
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("a.round", ParameterValue.of(BigDecimal.ONE)),
                        org.assertj.core.api.Assertions.entry("b.on", ParameterValue.of(false)),
                        org.assertj.core.api.Assertions.entry("c.off", ParameterValue.of(true)));
    }
}
