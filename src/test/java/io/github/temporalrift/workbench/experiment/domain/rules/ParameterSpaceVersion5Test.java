package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.VariantRulesReason;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.BooleanParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.NumberParameter;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** Parameter space version 5: space 4 plus the balance-package settings and their new baselines. */
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
    private static final String SECRET_WON = "game.rules.scoring.score-deltas.SECRET_OUTCOME_WON";
    private static final String MIMIC_NEVER = "game.rules.scoring.score-deltas.MIMIC_NEVER_TRACED";
    private static final String MIMIC_WIN = "game.rules.scoring.score-deltas.MIMIC_CONTRIBUTED_TO_WIN";
    private static final List<String> REVISIONIST_BASELINES = List.of(SECRET_WON, MIMIC_NEVER, MIMIC_WIN);
    private static final List<String> NEW_KEYS =
            List.of(EVENTS_3, EVENTS_4, EVENTS_5, VOIDS, CONTEST, DETONATED, WASTED, TIEBREAK, DISCOUNT);

    /**
     * The document the lanes serve: the configuration repository's {@code application.yml} and service files resolved
     * with {@code scripts/lib/effective_rules.py resolve --config-repo config-server/config-repo --output <file>} in
     * the infrastructure repository, at the commit that serves the balance package. Space 5's baselines are checked
     * against it as it is, never against a patched copy of the shared document older spaces still need.
     */
    private static final EffectiveRules BASELINE = served();

    private final ParameterSpace four = VariantRulesFixtures.spaces().find(4).orElseThrow();
    private final ParameterSpace five = VariantRulesFixtures.spaces().find(5).orElseThrow();

    private static EffectiveRules served() {
        try (var stream = ParameterSpaceVersion5Test.class.getResourceAsStream("/rules/served-effective-rules.json")) {
            var document = (ObjectNode) new ObjectMapper().readTree(stream);
            // The shared document now serves the rebalance values; space 5 still has its own baselines.
            ((ObjectNode) document.get("properties"))
                    .put(WEAVER_CHAIN, 3)
                    .put(SECRET_WON, 3)
                    .remove(List.of(
                            "game.rules.scoring.score-deltas.CHAIN_LINK_MISSED",
                            "game.rules.victory.eraser-annihilations-by-player-count.5",
                            "game.rules.victory.activist-consecutive-eras-by-player-count.5"));
            return EffectiveRules.parse(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Test
    void spaceFiveIsServed() {
        assertThat(VariantRulesFixtures.spaces().find(5)).isPresent();
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
    void theRevisionistBaselinesAreTheValuesTheServicesNowServeAndKeepTheirRanges() {
        assertThat(five.parameter(SECRET_WON))
                .contains(new NumberParameter(SECRET_WON, List.of("game-service"), n("3"), n("3"), n("5"), n("1")));
        assertThat(five.parameter(MIMIC_NEVER))
                .contains(new NumberParameter(MIMIC_NEVER, List.of("game-service"), n("4"), n("4"), n("8"), n("1")));
        assertThat(five.parameter(MIMIC_WIN))
                .contains(new NumberParameter(MIMIC_WIN, List.of("game-service"), n("1"), n("1"), n("3"), n("1")));
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
                .filter(parameter -> !REVISIONIST_BASELINES.contains(parameter.key()))
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
                .isInstanceOfSatisfying(
                        VariantRulesRefusal.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(VariantRulesReason.OUT_OF_RANGE));
    }
}
