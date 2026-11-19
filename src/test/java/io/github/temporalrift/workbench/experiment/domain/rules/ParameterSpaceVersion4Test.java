package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Parameter space version 4: space 3 plus the faction-balance rule switches. */
class ParameterSpaceVersion4Test {

    private static final String WRITINGS = "game.rules.foresight.writings-per-era";
    private static final String CLAIMS = "game.rules.stabilization.faction-claims";
    private static final String TAPESTRY = "game.rules.tapestry-absorbs-chain-conflict";
    private static final String FATE = "game.rules.paradox.fate-contradiction";
    private static final String RALLY_MISS = "game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED_WITH_RALLY";
    private static final String MISS = "game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED";
    private static final String STABILIZE = "game.rules.scoring.score-deltas.PARADOX_STABILIZED";
    private static final String PROPHET_GOAL = "game.rules.victory.prophet-written-resolutions";
    private static final String WEAVER_CHAIN = "game.rules.victory.weaver-chain-length";

    /** The shared test document predates the Prophet objective of 3 and Weaver chain of 4 the lanes serve. */
    private static final EffectiveRules BASELINE = served();

    private final ParameterSpace three = VariantRulesFixtures.spaces().find(3).orElseThrow();
    private final ParameterSpace four = VariantRulesFixtures.spaces().find(4).orElseThrow();

    private static EffectiveRules served() {
        var document = (ObjectNode) new ObjectMapper().readTree(VariantRulesFixtures.baselineText());
        // The shared document now serves the balance-package baselines; space 4 still has its own.
        ((ObjectNode) document.get("properties"))
                .put(PROPHET_GOAL, 3)
                .put(WEAVER_CHAIN, 4)
                .put(STABILIZE, -1);
        return EffectiveRules.parse(document);
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Test
    void spaceFourIsServedAfterSpacesOneToThree() {
        assertThat(VariantRulesFixtures.spaces().all())
                .extracting(ParameterSpace::version)
                .startsWith(1, 2, 3, 4);
    }

    @Test
    void theForesightWritingCapIsASharedNumberFromOneToThree() {
        assertThat(four.parameter(WRITINGS))
                .contains(new NumberParameter(
                        WRITINGS, List.of("game-service", "timeline-service"), n("1"), n("1"), n("3"), n("1")));
    }

    @Test
    void theRuleSwitchesAreBooleansWithTheServedDefaults() {
        assertThat(four.parameter(CLAIMS)).contains(new BooleanParameter(CLAIMS, List.of("game-service"), false));
        assertThat(four.parameter(TAPESTRY))
                .contains(new BooleanParameter(TAPESTRY, List.of("timeline-service"), false));
        assertThat(four.parameter(FATE)).contains(new BooleanParameter(FATE, List.of("timeline-service"), true));
    }

    @Test
    void theThreeNewScoreDeltasAreNumbersThatCanBeSwitchedOffAtZero() {
        assertThat(four.parameter(RALLY_MISS))
                .contains(new NumberParameter(RALLY_MISS, List.of("game-service"), n("-2"), n("-4"), n("0"), n("1")));
        assertThat(four.parameter(MISS))
                .contains(new NumberParameter(MISS, List.of("game-service"), n("-1"), n("-3"), n("0"), n("1")));
        assertThat(four.parameter(STABILIZE))
                .contains(new NumberParameter(STABILIZE, List.of("game-service"), n("-1"), n("-3"), n("0"), n("1")));
    }

    @Test
    void spaceThreeNamesNoneOfTheNewSettings() {
        List.of(WRITINGS, CLAIMS, TAPESTRY, FATE, RALLY_MISS, MISS, STABILIZE)
                .forEach(key -> assertThat(three.parameter(key)).as(key).isEmpty());
    }

    @Test
    void everyOtherSpaceThreeParameterIsCarriedOverUnchanged() {
        var carried = three.parameters().stream()
                .filter(parameter -> !parameter.key().equals(PROPHET_GOAL))
                .filter(parameter -> !parameter.key().equals(WEAVER_CHAIN))
                .toList();

        assertThat(four.parameters())
                .containsAll(carried)
                .hasSize(three.parameters().size() + 7);
        assertThat(four.derived()).isEqualTo(three.derived());
        assertThat(four.heldFixed()).isEqualTo(three.heldFixed());
    }

    @Test
    void theBaselinesOfTheProphetGoalAndTheWeaverChainAreTheValuesTheLanesNowServe() {
        assertThat(four.parameter(PROPHET_GOAL)).isPresent();
        assertThat(four.parameter(PROPHET_GOAL).orElseThrow().baselineValue()).isEqualTo(ParameterValue.of(n("3")));
        assertThat(four.parameter(WEAVER_CHAIN).orElseThrow().baselineValue()).isEqualTo(ParameterValue.of(n("4")));
    }

    @Test
    void theFactionClaimThresholdsAreNoLongerBoundToTheObjectives() {
        assertThat(four.constraints())
                .extracting(ParameterSpace.Constraint::id)
                .doesNotContain("C-06", "C-07")
                .contains("C-01", "C-02", "C-04", "C-13", "C-14");
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        var baseline = BASELINE;

        four.parameters()
                .forEach(parameter -> assertThat(baseline.value(parameter.key()))
                        .as(parameter.key())
                        .contains(parameter.baselineValue()));
    }

    @Test
    void switchingOneRuleBackChangesTheEffectiveRulesDigest() {
        var baseline = BASELINE;

        var restored = VariantMaterializer.materialize(baseline, four, Map.of(WRITINGS, ParameterValue.of(n("3"))));

        assertThat(restored.digest()).isNotEqualTo(baseline.digest());
    }
}
