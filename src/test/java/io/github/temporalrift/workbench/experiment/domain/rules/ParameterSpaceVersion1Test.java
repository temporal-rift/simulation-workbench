package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.HeldFixed;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;

/** Parameter space version 1: its derived keys, and baselines that are the lanes' served values. */
class ParameterSpaceVersion1Test {

    private static final String SCORE_DELTAS = "game.rules.scoring.score-deltas.";

    private final ParameterSpace space = VariantRulesFixtures.spaces().find(1).orElseThrow();

    @Test
    void itsDerivedKeysMirrorTheirSources() {
        assertThat(space.derived())
                .extracting(
                        ParameterSpace.Derived::key,
                        ParameterSpace.Derived::from,
                        d -> d.factor().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "game.rules.probability.suppress-shift.I", "game.rules.probability.push-shift.I", -1),
                        org.assertj.core.groups.Tuple.tuple(
                                "game.rules.probability.suppress-shift.II", "game.rules.probability.push-shift.II", -1),
                        org.assertj.core.groups.Tuple.tuple(
                                "game.rules.probability.suppress-shift.III",
                                "game.rules.probability.push-shift.III",
                                -1),
                        org.assertj.core.groups.Tuple.tuple(
                                SCORE_DELTAS + "EXPOSE_CHANGED_PLAYER_BEHAVIOR",
                                SCORE_DELTAS + "EXPOSE_SIGNATURE_REVEALED",
                                1));
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        var baseline = VariantRulesFixtures.baseline();

        space.parameters()
                .forEach(parameter -> assertThat(baseline.number(parameter.key()))
                        .as(parameter.key())
                        .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(parameter.baseline())));
    }

    @Test
    void everyDerivedValueOfTheBaselineIsItsFactorTimesItsSource() {
        var baseline = VariantRulesFixtures.baseline();

        space.derived().stream()
                .filter(derived -> baseline.number(derived.key()).isPresent())
                .forEach(derived -> assertThat(baseline.number(derived.key()).orElseThrow())
                        .as(derived.key())
                        .isEqualByComparingTo(
                                baseline.number(derived.from()).orElseThrow().multiply(derived.factor())));
    }

    @Test
    void everyConstraintHoldsOnTheBaselineAndReadsOnlyServedKeys() {
        var baseline = VariantRulesFixtures.baseline();

        space.constraints().forEach(constraint -> {
            assertThat(constraint.expression().holds(baseline))
                    .as(constraint.id())
                    .isTrue();
            assertThat(constraint.expression().keys().stream()
                            .filter(key -> baseline.number(key).isEmpty())
                            .toList())
                    .as(constraint.id())
                    .isEmpty();
        });
    }

    @Test
    void noHeldFixedPropertyIsAlsoAParameter() {
        var parameters = space.parameters().stream().map(Parameter::key).toList();

        assertThat(space.heldFixed()).extracting(HeldFixed::key).doesNotContainAnyElementsOf(parameters);
    }
}
