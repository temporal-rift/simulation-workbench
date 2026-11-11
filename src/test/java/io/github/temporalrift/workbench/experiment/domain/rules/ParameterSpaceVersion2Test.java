package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.BooleanParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.NumberParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;

/** Parameter space version 2: space 1 plus the Eraser and Prophet balance rules. */
class ParameterSpaceVersion2Test {

    private final ParameterSpace one = VariantRulesFixtures.spaces().find(1).orElseThrow();
    private final ParameterSpace two = VariantRulesFixtures.spaces().find(2).orElseThrow();

    @Test
    void spaceTwoHoldsEverySpaceOneParameterUnchangedAndThreeMore() {
        assertThat(two.parameters())
                .containsAll(one.parameters())
                .hasSize(one.parameters().size() + 3);
        assertThat(two.derived()).isEqualTo(one.derived());
        assertThat(two.constraints())
                .extracting(c -> c.id() + " " + c.expression().source() + " " + c.description())
                .containsExactlyElementsOf(one.constraints().stream()
                        .map(c -> c.id() + " " + c.expression().source() + " " + c.description())
                        .toList());
        assertThat(two.heldFixed()).isEqualTo(one.heldFixed());
    }

    @Test
    void spaceOneIsStillAllNumeric() {
        assertThat(one.version()).isEqualTo(1);
        assertThat(one.parameters()).hasSize(46).allMatch(NumberParameter.class::isInstance);
    }

    @Test
    void theEarliestAnnihilateRoundIsANumberFromOneToThree() {
        assertThat(two.parameter("game.rules.annihilate-earliest-round"))
                .contains(new NumberParameter(
                        "game.rules.annihilate-earliest-round",
                        List.of("game-service"),
                        new BigDecimal("2"),
                        new BigDecimal("1"),
                        new BigDecimal("3"),
                        new BigDecimal("1")));
    }

    @Test
    void theTwoSwitchesAreBooleansReadByTheirServicesAndOnByDefault() {
        assertThat(two.parameter("game.rules.scoring.written-outcome-shields-annihilation"))
                .contains(new BooleanParameter(
                        "game.rules.scoring.written-outcome-shields-annihilation", List.of("game-service"), true));
        assertThat(two.parameter("game.rules.probability.erased-weight-to-written-outcome"))
                .contains(new BooleanParameter(
                        "game.rules.probability.erased-weight-to-written-outcome", List.of("timeline-service"), true));
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        var baseline = VariantRulesFixtures.baseline();

        two.parameters()
                .forEach(parameter -> assertThat(baseline.value(parameter.key()))
                        .as(parameter.key())
                        .contains(parameter.baselineValue()));
    }

    @Test
    void aDerivedValueMayNotDeriveFromABooleanParameter() {
        List<Parameter> parameters = List.of(new BooleanParameter("game.rules.flag", List.of("game-service"), true));
        var derived = List.of(new ParameterSpace.Derived("game.rules.alias", "game.rules.flag", BigDecimal.ONE));

        assertThatThrownBy(() -> new ParameterSpace(3, parameters, derived, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a numeric parameter");
    }
}
