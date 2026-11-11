package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

class VariantMaterializerTest {

    private static final String PUSH_II = "game.rules.probability.push-shift.II";
    private static final String SUPPRESS_II = "game.rules.probability.suppress-shift.II";
    private static final String THRESHOLD = "game.rules.win-score-threshold";
    private static final String PARADOX_WEIGHT = "game.rules.card-category-weights.PARADOX";

    private final EffectiveRules baseline = VariantRulesFixtures.baseline();
    private final ParameterSpace space = VariantRulesFixtures.spaces().find(1).orElseThrow();

    private static Map<String, ParameterValue> overrides(Object... keysAndValues) {
        var overrides = new LinkedHashMap<String, ParameterValue>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            var value = keysAndValues[i + 1];
            overrides.put(
                    (String) keysAndValues[i],
                    value instanceof Boolean flag
                            ? ParameterValue.of(flag.booleanValue())
                            : ParameterValue.of(new BigDecimal(value.toString())));
        }
        return overrides;
    }

    private static final String EARLIEST_ROUND = "game.rules.annihilate-earliest-round";
    private static final String SHIELD = "game.rules.scoring.written-outcome-shields-annihilation";
    private static final String ERASED_WEIGHT = "game.rules.probability.erased-weight-to-written-outcome";

    private final ParameterSpace spaceTwo =
            VariantRulesFixtures.spaces().find(2).orElseThrow();

    private VariantMaterializer.Materialized materializeInSpaceTwo(Object... keysAndValues) {
        return VariantMaterializer.materialize(baseline, spaceTwo, overrides(keysAndValues));
    }

    @Test
    void aBooleanOverrideReplacesTheBaselineFlagAndLeavesTheRestOfTheRulesAlone() {
        var materialized = materializeInSpaceTwo(SHIELD, false);

        assertThat(materialized.rules().flag(SHIELD)).contains(false);
        assertThat(materialized.overrides()).containsExactly(Map.entry(SHIELD, ParameterValue.of(false)));
        assertThat(materialized.digest()).isNotEqualTo(baseline.digest());
        baseline.properties().forEach((key, value) -> {
            if (!key.equals(SHIELD)) {
                assertThat(materialized.rules().properties()).containsEntry(key, value);
            }
        });
    }

    @Test
    void aBooleanOverrideEqualToTheBaselineIsDropped() {
        var materialized = materializeInSpaceTwo(SHIELD, true, ERASED_WEIGHT, true);

        assertThat(materialized.overrides()).isEmpty();
        assertThat(materialized.rules()).isEqualTo(baseline);
    }

    @Test
    void numericAndBooleanOverridesApplyTogether() {
        var materialized = materializeInSpaceTwo(EARLIEST_ROUND, 1, ERASED_WEIGHT, false);

        assertThat(materialized.rules().number(EARLIEST_ROUND)).contains(new BigDecimal("1"));
        assertThat(materialized.rules().flag(ERASED_WEIGHT)).contains(false);
        assertThat(materialized.overrides().keySet()).containsExactly(EARLIEST_ROUND, ERASED_WEIGHT);
    }

    @Test
    void aNumberForABooleanParameterIsTheWrongType() {
        var refusal = catchRefusal(() -> materializeInSpaceTwo(SHIELD, 1));

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.WRONG_TYPE);
        assertThat(refusal.overrideKey()).contains(SHIELD);
    }

    @Test
    void aBooleanForANumericParameterIsTheWrongType() {
        var refusal = catchRefusal(() -> materializeInSpaceTwo(THRESHOLD, true));

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.WRONG_TYPE);
        assertThat(refusal.overrideKey()).contains(THRESHOLD);
    }

    @Test
    void anEarliestRoundOutsideOneToThreeIsOutOfRange() {
        assertThat(catchRefusal(() -> materializeInSpaceTwo(EARLIEST_ROUND, 4)).reason())
                .isEqualTo(VariantRulesReason.OUT_OF_RANGE);
        assertThat(catchRefusal(() -> materializeInSpaceTwo(EARLIEST_ROUND, 0)).reason())
                .isEqualTo(VariantRulesReason.OUT_OF_RANGE);
    }

    @Test
    void theSameNumericOverridesGiveTheSameRulesInSpaceOneAndSpaceTwo() {
        assertThat(materializeInSpaceTwo(THRESHOLD, 22, PUSH_II, 25).digest())
                .isEqualTo(materialize(THRESHOLD, 22, PUSH_II, 25).digest());
    }

    @Test
    void aBaselineWithoutTheBalanceRulesCannotCarrySpaceTwo() {
        var withoutFlags = new TreeMap<>(baseline.properties());
        withoutFlags.remove(SHIELD);
        var incomplete = new EffectiveRules(withoutFlags);
        var overrides = overrides(THRESHOLD, 22);

        var refusal = catchRefusal(() -> VariantMaterializer.materialize(incomplete, spaceTwo, overrides));

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.UNKNOWN_BASELINE);
    }

    private VariantMaterializer.Materialized materialize(Object... keysAndValues) {
        return VariantMaterializer.materialize(baseline, space, overrides(keysAndValues));
    }

    private VariantRulesRefusal refusal(Object... keysAndValues) {
        return catchRefusal(() -> materialize(keysAndValues));
    }

    private static VariantRulesRefusal catchRefusal(Runnable materialization) {
        var thrown = org.assertj.core.api.Assertions.catchThrowable(materialization::run);
        assertThat(thrown).isInstanceOf(VariantRulesRefusal.class);
        return (VariantRulesRefusal) thrown;
    }

    @Test
    void anOverrideReplacesTheBaselineValueAndSetsItsDerivedValue() {
        var materialized = materialize(PUSH_II, 25);

        var expected = new TreeMap<>(baseline.properties());
        expected.put(PUSH_II, JsonNodeFactory.instance.numberNode(25));
        expected.put(SUPPRESS_II, JsonNodeFactory.instance.numberNode(-25));
        assertThat(materialized.rules().number(PUSH_II)).contains(new BigDecimal("25"));
        assertThat(materialized.rules().number(SUPPRESS_II)).contains(new BigDecimal("-25"));
        assertThat(materialized.digest()).isEqualTo(new EffectiveRules(expected).digest());
        assertThat(materialized.digest()).isNotEqualTo(baseline.digest());
        assertThat(materialized.overrides())
                .containsExactly(Map.entry(PUSH_II, ParameterValue.of(new BigDecimal("25"))));
    }

    @Test
    void everyOtherKeyEqualsTheBaseline() {
        var rules = materialize(PUSH_II, 25).rules().properties();

        baseline.properties().forEach((key, value) -> {
            if (!key.equals(PUSH_II) && !key.equals(SUPPRESS_II)) {
                assertThat(rules).containsEntry(key, value);
            }
        });
        assertThat(rules).hasSameSizeAs(baseline.properties());
    }

    @Test
    void anOverrideEqualToTheBaselineIsDropped() {
        var materialized = materialize(THRESHOLD, 20);

        assertThat(materialized.overrides()).isEmpty();
        assertThat(materialized.digest()).isEqualTo(baseline.digest());
        assertThat(materialized.rules()).isEqualTo(baseline);
    }

    @Test
    void noOverridesIsTheBaselineItself() {
        var materialized = VariantMaterializer.materialize(baseline, space, Map.of());

        assertThat(materialized.rules()).isSameAs(baseline);
        assertThat(materialized.overrides()).isEmpty();
    }

    @Test
    void aValueIsTheSameWhateverItsSpelling() {
        assertThat(materialize(PUSH_II, "25.0").digest())
                .isEqualTo(materialize(PUSH_II, 25).digest());
        assertThat(materialize(PUSH_II, "25.0").overrides())
                .containsEntry(PUSH_II, ParameterValue.of(new BigDecimal("25")));
    }

    @Test
    void theSameInputsGiveTheSameRulesInAnyKeyOrder() {
        var forward = materialize(THRESHOLD, 22, PUSH_II, 25, "game.rules.cards-per-deal", 8);
        var reverse = materialize("game.rules.cards-per-deal", 8, PUSH_II, 25, THRESHOLD, 22);

        assertThat(reverse.rules()).isEqualTo(forward.rules());
        assertThat(reverse.digest()).isEqualTo(forward.digest());
        assertThat(reverse.overrides().keySet())
                .containsExactlyElementsOf(forward.overrides().keySet());
    }

    @Test
    void aDecimalParameterIsOverriddenOnItsStep() {
        var materialized = materialize("game.rules.probability.amplify-multiplier.II", "2.25");

        assertThat(materialized.rules().number("game.rules.probability.amplify-multiplier.II"))
                .contains(new BigDecimal("2.25"));
    }

    @Test
    void aValueEqualToTheBaselineUnderAnotherScaleIsStillTheBaseline() {
        assertThat(materialize("game.rules.probability.amplify-multiplier.II", "2.00")
                        .overrides())
                .isEmpty();
    }

    @Test
    void theAliasOfAScoreFollowsItsSource() {
        var materialized = materialize("game.rules.scoring.score-deltas.EXPOSE_SIGNATURE_REVEALED", 3);

        assertThat(materialized.rules().number("game.rules.scoring.score-deltas.EXPOSE_CHANGED_PLAYER_BEHAVIOR"))
                .contains(new BigDecimal("3"));
    }

    @Test
    void aValueAboveTheRangeIsOutOfRange() {
        var refusal = refusal(THRESHOLD, 27);

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.OUT_OF_RANGE);
        assertThat(refusal.overrideKey()).contains(THRESHOLD);
        assertThat(refusal.constraintId()).isEmpty();
    }

    @Test
    void aValueBelowTheRangeIsOutOfRange() {
        assertThat(refusal(THRESHOLD, 15).reason()).isEqualTo(VariantRulesReason.OUT_OF_RANGE);
    }

    @Test
    void aValueOffTheStepIsOffStep() {
        var refusal = refusal(PARADOX_WEIGHT, 12);

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.OFF_STEP);
        assertThat(refusal.overrideKey()).contains(PARADOX_WEIGHT);
    }

    @Test
    void aHeldFixedParameterIsNotAParameter() {
        var refusal = refusal("game.rules.max-eras", 4);

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.UNKNOWN_PARAMETER);
        assertThat(refusal.overrideKey()).contains("game.rules.max-eras");
    }

    @Test
    void aDerivedKeyIsNotAParameter() {
        assertThat(refusal("game.rules.probability.suppress-shift.I", -10).reason())
                .isEqualTo(VariantRulesReason.UNKNOWN_PARAMETER);
    }

    @Test
    void weightsThatDoNotSumTo100BreakConstraintC11() {
        var refusal = refusal(PARADOX_WEIGHT, 20);

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.CONSTRAINT_VIOLATED);
        assertThat(refusal.constraintId()).contains("C-11");
        assertThat(refusal.overrideKey()).contains(PARADOX_WEIGHT);
    }

    @Test
    void aDealBeyondThreeMoreThanTheHandBreaksConstraintC08() {
        var refusal = refusal("game.rules.cards-per-hand", 4, "game.rules.cards-per-deal", 8);

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.CONSTRAINT_VIOLATED);
        assertThat(refusal.constraintId()).contains("C-08");
        assertThat(refusal.overrideKey()).contains("game.rules.cards-per-deal");
    }

    @Test
    void aConstraintNamesTheFirstOverriddenKeyItReads() {
        var refusal = refusal("game.rules.probability.push-shift.II", 15, "game.rules.probability.push-shift.I", 15);

        assertThat(refusal.constraintId()).contains("C-10");
        assertThat(refusal.overrideKey()).contains("game.rules.probability.push-shift.I");
    }

    @Test
    void theFirstRefusalIsTheFirstKeyInAscendingOrder() {
        var refusal = refusal(THRESHOLD, 27, "game.rules.cards-per-hand", 9);

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.OUT_OF_RANGE);
        assertThat(refusal.overrideKey()).contains("game.rules.cards-per-hand");
    }

    @Test
    void anOverrideIsCheckedBeforeAnyConstraint() {
        var refusal = refusal(PARADOX_WEIGHT, 20, THRESHOLD, 27);

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.OUT_OF_RANGE);
    }

    @Test
    void theFirstViolatedConstraintByIdIsReported() {
        var refusal = refusal(THRESHOLD, 16);

        assertThat(refusal.constraintId()).contains("C-02");
        assertThat(refusal.overrideKey()).contains(THRESHOLD);
    }

    @Test
    void aBaselineThatLacksAParameterIsUnusable() {
        var properties = new TreeMap<>(baseline.properties());
        properties.remove(THRESHOLD);

        var refusal = catchRefusal(
                () -> VariantMaterializer.materialize(new EffectiveRules(properties), space, overrides(PUSH_II, 25)));

        assertThat(refusal.reason()).isEqualTo(VariantRulesReason.UNKNOWN_BASELINE);
        assertThat(refusal.getMessage()).contains(THRESHOLD);
    }

    @Test
    void aWholeValueIsWrittenAsAnInteger() {
        JsonNode whole = ParameterValue.of(new BigDecimal("25.00")).json();
        JsonNode fraction = ParameterValue.of(new BigDecimal("2.50")).json();

        assertThat(whole.isIntegralNumber()).isTrue();
        assertThat(whole.longValue()).isEqualTo(25);
        assertThat(fraction.decimalValue()).isEqualByComparingTo("2.5");
    }

    @Test
    void theBaselineSatisfiesEveryConstraint() {
        space.constraints()
                .forEach(constraint -> assertThat(constraint.expression().holds(baseline))
                        .as(constraint.id())
                        .isTrue());
    }

    @Test
    void theRefusalNamesTheOffendingValueWithoutKnowingItsPlace() {
        var refusal = refusal(THRESHOLD, 27);

        var error = refusal.at(
                io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode.INVALID_EXPERIMENT,
                "variants[0].");

        assertThat(error.field()).contains("variants[0].overrides." + THRESHOLD);
        assertThat(error.reason()).contains(VariantRulesReason.OUT_OF_RANGE);
        assertThatThrownBy(() -> {
                    throw error;
                })
                .hasMessageContaining(THRESHOLD);
    }
}
