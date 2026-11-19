package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.BooleanParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.NumberParameter;

/** Parameter space version 6: space 5 plus the rebalance settings and their new baselines. */
class ParameterSpaceVersion6Test {

    private static final String LINK_MISSED = "game.rules.scoring.score-deltas.CHAIN_LINK_MISSED";
    private static final String ERASER_5 = "game.rules.victory.eraser-annihilations-by-player-count.5";
    private static final String ACTIVIST_5 = "game.rules.victory.activist-consecutive-eras-by-player-count.5";
    private static final String WEAVER_CHAIN = "game.rules.victory.weaver-chain-length";
    private static final String SECRET_WON = "game.rules.scoring.score-deltas.SECRET_OUTCOME_WON";
    private static final List<String> NEW_KEYS = List.of(LINK_MISSED, ERASER_5, ACTIVIST_5);

    /** The document the lanes serve, with the rebalance values; space 6's baselines are checked against it as it is. */
    private static final EffectiveRules BASELINE = served();

    private final ParameterSpace five = VariantRulesFixtures.spaces().find(5).orElseThrow();
    private final ParameterSpace six = VariantRulesFixtures.spaces().find(6).orElseThrow();

    private static EffectiveRules served() {
        try (var stream = ParameterSpaceVersion6Test.class.getResourceAsStream("/rules/served-effective-rules.json")) {
            return EffectiveRules.parse(new ObjectMapper().readTree(stream));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Test
    void theThreeNewSettingsHaveTheirServedBaselinesAndRanges() {
        assertThat(six.parameter(LINK_MISSED))
                .contains(new NumberParameter(LINK_MISSED, List.of("game-service"), n("-1"), n("-2"), n("0"), n("1")));
        assertThat(six.parameter(ERASER_5))
                .contains(new NumberParameter(ERASER_5, List.of("game-service"), n("3"), n("3"), n("5"), n("1")));
        assertThat(six.parameter(ACTIVIST_5))
                .contains(new BooleanParameter(ACTIVIST_5, List.of("game-service"), false));
    }

    @Test
    void theChainAndSecretOutcomeBaselinesMoveToTheRebalanceValues() {
        assertThat(six.parameter(WEAVER_CHAIN))
                .contains(new NumberParameter(
                        WEAVER_CHAIN, List.of("game-service", "timeline-service"), n("4"), n("3"), n("4"), n("1")));
        assertThat(six.parameter(SECRET_WON))
                .contains(new NumberParameter(SECRET_WON, List.of("game-service"), n("4"), n("3"), n("5"), n("1")));
    }

    @Test
    void spaceFiveNamesNoneOfTheNewSettings() {
        NEW_KEYS.forEach(key -> assertThat(five.parameter(key)).as(key).isEmpty());
    }

    @Test
    void everyOtherSpaceFiveParameterIsCarriedOverUnchanged() {
        var carried = five.parameters().stream()
                .filter(parameter -> !parameter.key().equals(WEAVER_CHAIN))
                .filter(parameter -> !parameter.key().equals(SECRET_WON))
                .toList();

        assertThat(six.parameters())
                .containsAll(carried)
                .hasSize(five.parameters().size() + NEW_KEYS.size());
        assertThat(six.derived()).isEqualTo(five.derived());
        assertThat(constraintTexts(six)).isEqualTo(constraintTexts(five));
    }

    private static List<String> constraintTexts(ParameterSpace space) {
        return space.constraints().stream()
                .map(constraint ->
                        constraint.id() + " " + constraint.expression().source() + " " + constraint.description())
                .toList();
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        six.parameters()
                .forEach(parameter -> assertThat(BASELINE.value(parameter.key()))
                        .as(parameter.key())
                        .contains(parameter.baselineValue()));
    }
}
