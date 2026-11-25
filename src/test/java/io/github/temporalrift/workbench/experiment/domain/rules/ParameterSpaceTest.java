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

/** The parameter spaces the workbench serves: the settings, baselines and ranges of each. */
class ParameterSpaceTest {

    private static final String LINK_MISSED = "game.rules.scoring.score-deltas.CHAIN_LINK_MISSED";
    private static final String ERASER_5 = "game.rules.victory.eraser-annihilations-by-player-count.5";
    private static final String ACTIVIST_5 = "game.rules.victory.activist-consecutive-eras-by-player-count.5";
    private static final String BET_WINDOW = "game.rules.faction-bet-latest-round";
    private static final String REWRITE_WINDOW = "game.rules.rewrite-latest-round";
    private static final String THREAD_SHIFT = "game.rules.probability.thread-shift";
    private static final String DISCOUNT = "game.rules.victory.final-era-objective-discount";
    private static final String WEAVER_CHAIN = "game.rules.victory.weaver-chain-length";
    private static final String SECRET_WON = "game.rules.scoring.score-deltas.SECRET_OUTCOME_WON";

    /** The document the lanes serve; the space's baselines are checked against it as it is. */
    private static final EffectiveRules BASELINE = served();

    private final ParameterSpace space = VariantRulesFixtures.spaces().all().getFirst();

    private static EffectiveRules served() {
        try (var stream = ParameterSpaceTest.class.getResourceAsStream("/rules/served-effective-rules.json")) {
            return EffectiveRules.parse(new ObjectMapper().readTree(stream));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Test
    void exactlyOneSpaceIsServedAtVersionTwo() {
        var spaces = VariantRulesFixtures.spaces();

        assertThat(spaces.all()).extracting(ParameterSpace::version).containsExactly(2);
        assertThat(spaces.find(1)).isEmpty();
        assertThat(spaces.find(space.version())).map(ParameterSpace::version).contains(space.version());
        assertThat(spaces.find(space.version() + 1)).isEmpty();
    }

    @Test
    void theRebalanceSettingsHaveTheirServedBaselinesAndRanges() {
        assertThat(space.parameter(LINK_MISSED))
                .contains(new NumberParameter(LINK_MISSED, List.of("game-service"), n("-1"), n("-2"), n("0"), n("1")));
        assertThat(space.parameter(ERASER_5))
                .contains(new NumberParameter(ERASER_5, List.of("game-service"), n("4"), n("3"), n("5"), n("1")));
        assertThat(space.parameter(ACTIVIST_5))
                .contains(new BooleanParameter(ACTIVIST_5, List.of("game-service"), true));
        assertThat(space.parameter(WEAVER_CHAIN))
                .contains(new NumberParameter(
                        WEAVER_CHAIN, List.of("game-service", "timeline-service"), n("4"), n("3"), n("4"), n("1")));
        assertThat(space.parameter(SECRET_WON))
                .contains(new NumberParameter(SECRET_WON, List.of("game-service"), n("4"), n("3"), n("5"), n("1")));
    }

    @Test
    void theNewestSpaceHasTheBetWindowAndAThreadShiftThatMayBeZero() {
        assertThat(space.parameter(BET_WINDOW))
                .contains(new NumberParameter(
                        BET_WINDOW, List.of("game-service", "timeline-service"), n("2"), n("1"), n("3"), n("1")));
        assertThat(space.parameter(REWRITE_WINDOW)).isEmpty();
        assertThat(space.parameter(THREAD_SHIFT))
                .contains(new NumberParameter(
                        THREAD_SHIFT, List.of("timeline-service"), n("0"), n("0"), n("15"), n("5")));
        assertThat(space.parameter(DISCOUNT))
                .contains(new NumberParameter(DISCOUNT, List.of("game-service"), n("0"), n("0"), n("2"), n("1")));
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        space.parameters()
                .forEach(parameter -> assertThat(BASELINE.value(parameter.key()))
                        .as(parameter.key())
                        .contains(parameter.baselineValue()));
    }
}
