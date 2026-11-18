package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.BooleanParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.NumberParameter;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** Parameter space version 5: space 4 plus the 18-nov balance package settings and their new baselines. */
class ParameterSpaceVersion5Test {

    private static final String EVENTS_3 = "game.rules.events-per-era.3";
    private static final String EVENTS_4 = "game.rules.events-per-era.4";
    private static final String EVENTS_5 = "game.rules.events-per-era.5";
    private static final String VOIDS = "game.rules.scoring.erased-outcome-voids-bets";
    private static final String CONTEST = "game.rules.paradox.detonate-contests-stabilize";
    private static final String DETONATED = "game.rules.scoring.score-deltas.PARADOX_DETONATED";
    private static final String WASTED = "game.rules.scoring.score-deltas.ANNIHILATION_WASTED";
    private static final String TIEBREAK = "game.rules.victory.score-tiebreak";
    private static final String DISCOUNT = "game.rules.victory.final-era-objective-discount";
    private static final String STABILIZED = "game.rules.scoring.score-deltas.PARADOX_STABILIZED";
    private static final String WEAVER_CHAIN = "game.rules.victory.weaver-chain-length";
    private static final String PROPHET_GOAL = "game.rules.victory.prophet-written-resolutions";
    private static final List<String> NEW_KEYS =
            List.of(EVENTS_3, EVENTS_4, EVENTS_5, VOIDS, CONTEST, DETONATED, WASTED, TIEBREAK, DISCOUNT);

    /** The shared test document with the values the lanes serve once the balance package is deployed. */
    private static final EffectiveRules BASELINE = served();

    private final ParameterSpace four = VariantRulesFixtures.spaces().find(4).orElseThrow();
    private final ParameterSpace five = VariantRulesFixtures.spaces().find(5).orElseThrow();

    private static EffectiveRules served() {
        var document = (ObjectNode) new ObjectMapper().readTree(VariantRulesFixtures.baselineText());
        ((ObjectNode) document.get("properties"))
                .put(PROPHET_GOAL, 3)
                .put(WEAVER_CHAIN, 3)
                .put(STABILIZED, 0)
                .put(EVENTS_3, 3)
                .put(EVENTS_4, 4)
                .put(EVENTS_5, 5)
                .put(VOIDS, true)
                .put(CONTEST, true)
                .put(DETONATED, -1)
                .put(WASTED, 0)
                .put(TIEBREAK, true)
                .put(DISCOUNT, 1);
        return EffectiveRules.parse(document);
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Test
    void spaceFiveIsTheNewestSpace() {
        assertThat(VariantRulesFixtures.spaces().all())
                .extracting(ParameterSpace::version)
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void eventsPerEraIsOneNumberPerPlayerCountFromThreeToFive() {
        assertThat(five.parameter(EVENTS_3))
                .contains(new NumberParameter(EVENTS_3, List.of("game-service"), n("3"), n("3"), n("5"), n("1")));
        assertThat(five.parameter(EVENTS_4))
                .contains(new NumberParameter(EVENTS_4, List.of("game-service"), n("4"), n("3"), n("5"), n("1")));
        assertThat(five.parameter(EVENTS_5))
                .contains(new NumberParameter(EVENTS_5, List.of("game-service"), n("5"), n("3"), n("5"), n("1")));
        assertThat(five.heldFixed())
                .extracting(ParameterSpace.HeldFixed::key)
                .doesNotContain("game.rules.events-per-era");
    }

    @Test
    void theRuleSwitchesAreBooleansThatShipOn() {
        assertThat(five.parameter(VOIDS)).contains(new BooleanParameter(VOIDS, List.of("game-service"), true));
        assertThat(five.parameter(CONTEST))
                .contains(new BooleanParameter(CONTEST, List.of("game-service", "timeline-service"), true));
        assertThat(five.parameter(TIEBREAK)).contains(new BooleanParameter(TIEBREAK, List.of("game-service"), true));
    }

    @Test
    void theNewNumbersHaveTheirServedBaselinesAndRanges() {
        assertThat(five.parameter(DETONATED))
                .contains(new NumberParameter(DETONATED, List.of("game-service"), n("-1"), n("-3"), n("0"), n("1")));
        assertThat(five.parameter(WASTED))
                .contains(new NumberParameter(WASTED, List.of("game-service"), n("0"), n("-2"), n("0"), n("1")));
        assertThat(five.parameter(DISCOUNT))
                .contains(new NumberParameter(DISCOUNT, List.of("game-service"), n("1"), n("0"), n("2"), n("1")));
    }

    @Test
    void theChainAndStabilizeBaselinesMoveToTheBalancePackageValues() {
        assertThat(five.parameter(WEAVER_CHAIN))
                .contains(new NumberParameter(
                        WEAVER_CHAIN, List.of("game-service", "timeline-service"), n("3"), n("3"), n("4"), n("1")));
        assertThat(five.parameter(STABILIZED).orElseThrow().baselineValue()).isEqualTo(ParameterValue.of(n("0")));
    }

    @Test
    void spaceFourNamesNoneOfTheNewSettings() {
        NEW_KEYS.forEach(key -> assertThat(four.parameter(key)).as(key).isEmpty());
    }

    @Test
    void everyOtherSpaceFourParameterIsCarriedOverUnchanged() {
        var carried = four.parameters().stream()
                .filter(parameter -> !parameter.key().equals(WEAVER_CHAIN))
                .filter(parameter -> !parameter.key().equals(STABILIZED))
                .toList();

        assertThat(five.parameters())
                .containsAll(carried)
                .hasSize(four.parameters().size() + NEW_KEYS.size());
        assertThat(five.derived()).isEqualTo(four.derived());
        assertThat(constraintTexts(five)).isEqualTo(constraintTexts(four));
    }

    private static List<String> constraintTexts(ParameterSpace space) {
        return space.constraints().stream()
                .map(constraint ->
                        constraint.id() + " " + constraint.expression().source() + " " + constraint.description())
                .toList();
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        five.parameters()
                .forEach(parameter -> assertThat(BASELINE.value(parameter.key()))
                        .as(parameter.key())
                        .contains(parameter.baselineValue()));
    }

    @Test
    void switchingTheContestOffChangesTheEffectiveRulesDigest() {
        var restored = VariantMaterializer.materialize(BASELINE, five, Map.of(CONTEST, ParameterValue.of(false)));

        assertThat(restored.digest()).isNotEqualTo(BASELINE.digest());
    }

    @Test
    void sixEventsPerEraIsOutOfRange() {
        assertThatThrownBy(() ->
                        VariantMaterializer.materialize(BASELINE, five, Map.of(EVENTS_5, ParameterValue.of(n("6")))))
                .isInstanceOf(RuntimeException.class);
    }
}
