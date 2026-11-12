package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.BooleanParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.NumberParameter;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** Parameter space version 3: space 2 plus the contested-yield rule settings. */
class ParameterSpaceVersion3Test {

    private static final String REWRITE_ROUND = "game.rules.rewrite-latest-round";
    private static final String MISSED = "game.rules.scoring.score-deltas.SECRET_OUTCOME_MISSED";
    private static final String EXPOSE = "game.rules.scoring.expose-requires-declared-event";
    private static final String ANNIHILATION = "game.rules.scoring.annihilation-requires-consequence";

    private final ParameterSpace two = VariantRulesFixtures.spaces().find(2).orElseThrow();
    private final ParameterSpace three = VariantRulesFixtures.spaces().find(3).orElseThrow();

    private static String describe(ParameterSpace.Constraint c) {
        return c.id() + " " + c.expression().source() + " " + c.description();
    }

    @Test
    void spacesOneTwoAndThreeAreServed() {
        assertThat(VariantRulesFixtures.spaces().all())
                .extracting(ParameterSpace::version)
                .containsExactly(1, 2, 3);
    }

    @Test
    void spaceThreeHoldsEverySpaceTwoEntryUnchangedAndFourMoreParameters() {
        assertThat(three.parameters())
                .containsAll(two.parameters())
                .hasSize(two.parameters().size() + 4);
        assertThat(three.derived()).isEqualTo(two.derived());
        assertThat(three.constraints())
                .extracting(ParameterSpaceVersion3Test::describe)
                .containsExactlyElementsOf(two.constraints().stream()
                        .map(ParameterSpaceVersion3Test::describe)
                        .toList());
        assertThat(three.heldFixed()).isEqualTo(two.heldFixed());
    }

    @Test
    void theRewriteRoundWindowIsANumberFromOneToThree() {
        assertThat(three.parameter(REWRITE_ROUND))
                .contains(new NumberParameter(
                        REWRITE_ROUND,
                        List.of("game-service"),
                        new BigDecimal("2"),
                        new BigDecimal("1"),
                        new BigDecimal("3"),
                        new BigDecimal("1")));
    }

    @Test
    void theMissedSecretOutcomeValueIsANumberFromMinusThreeToZero() {
        assertThat(three.parameter(MISSED))
                .contains(new NumberParameter(
                        MISSED,
                        List.of("game-service"),
                        new BigDecimal("-1"),
                        new BigDecimal("-3"),
                        new BigDecimal("0"),
                        new BigDecimal("1")));
    }

    @Test
    void theTwoSwitchesAreBooleansReadByGameServiceAndOnByDefault() {
        assertThat(three.parameter(EXPOSE)).contains(new BooleanParameter(EXPOSE, List.of("game-service"), true));
        assertThat(three.parameter(ANNIHILATION))
                .contains(new BooleanParameter(ANNIHILATION, List.of("game-service"), true));
    }

    @Test
    void spaceTwoNamesNoneOfTheNewSettings() {
        assertThat(two.parameter(REWRITE_ROUND)).isEmpty();
        assertThat(two.parameter(MISSED)).isEmpty();
        assertThat(two.parameter(EXPOSE)).isEmpty();
        assertThat(two.parameter(ANNIHILATION)).isEmpty();
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        var baseline = VariantRulesFixtures.baseline();

        three.parameters()
                .forEach(parameter -> assertThat(baseline.value(parameter.key()))
                        .as(parameter.key())
                        .contains(parameter.baselineValue()));
    }

    @Test
    void aWiderRewriteWindowChangesTheEffectiveRulesDigest() {
        var baseline = VariantRulesFixtures.baseline();

        var widened = VariantMaterializer.materialize(
                baseline, three, Map.of(REWRITE_ROUND, ParameterValue.of(new BigDecimal("3"))));

        assertThat(widened.digest()).isNotEqualTo(baseline.digest());
    }

    @Test
    void aMissedValueBelowTheRangeIsRefusedOutOfRange() {
        var baseline = VariantRulesFixtures.baseline();
        var overrides = Map.of(MISSED, ParameterValue.of(new BigDecimal("-4")));

        assertThatThrownBy(() -> VariantMaterializer.materialize(baseline, three, overrides))
                .isInstanceOfSatisfying(
                        VariantRulesRefusal.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(VariantRulesReason.OUT_OF_RANGE));
    }
}
