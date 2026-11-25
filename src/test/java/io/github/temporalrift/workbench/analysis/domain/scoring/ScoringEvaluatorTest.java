package io.github.temporalrift.workbench.analysis.domain.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.Faction;

class ScoringEvaluatorTest {

    private static ScoringModel symmetric(Faction boosted, double boostedWin, double boostedMiss) {
        var factions = new ArrayList<FactionScoring>();
        var rates = new ArrayList<BetRates>();
        for (var faction : Faction.values()) {
            factions.add(new FactionScoring(faction, List.of(4), 0, Set.of(), null, 0));
            for (var count : List.of(3, 4, 5)) {
                var boost = faction == boosted;
                rates.add(new BetRates(count, faction, boost ? boostedWin : 0.5, boost ? boostedMiss : 0.5, 0));
            }
        }
        return new ScoringModel(new ScoringEngine(12, 5, factions), List.of(3, 4, 5), rates, 20_000, 7);
    }

    @Test
    void identicalFactionsWinInProportionToTheirSeats() {
        var evaluation = ScoringEvaluator.evaluate(symmetric(null, 0.5, 0.5));

        assertThat(evaluation.cells())
                .hasSize(15)
                .allSatisfy(cell -> assertThat(cell.ratio()).isBetween(0.95, 1.05));
    }

    @Test
    void aFactionThatWinsItsBetMoreOftenWinsMore() {
        var evaluation = ScoringEvaluator.evaluate(symmetric(Faction.WEAVERS, 0.6, 0.4));

        assertThat(evaluation.cells())
                .filteredOn(cell -> cell.faction() == Faction.WEAVERS)
                .hasSize(3)
                .allSatisfy(cell -> assertThat(cell.ratio()).isGreaterThan(1.1));
    }

    @Test
    void theIntervalBracketsTheRatio() {
        var evaluation = ScoringEvaluator.evaluate(symmetric(null, 0.5, 0.5));

        assertThat(evaluation.cells()).allSatisfy(cell -> {
            assertThat(cell.lower()).isLessThan(cell.ratio());
            assertThat(cell.upper()).isGreaterThan(cell.ratio());
        });
    }

    @Test
    void everyRaceEndsByTheThresholdOrInTheFinalEra() {
        var evaluation = ScoringEvaluator.evaluate(symmetric(null, 0.5, 0.5));

        assertThat(evaluation.endings()).hasSize(3).allSatisfy(ending -> {
            assertThat(ending.thresholdShare() + ending.finalEraShare()).isEqualTo(1.0);
            assertThat(ending.meanEras()).isBetween(1.0, 5.0);
        });
    }

    @Test
    void anUnreachableThresholdAlwaysEndsInTheFinalEra() {
        var base = symmetric(null, 0.5, 0.5);
        var model = new ScoringModel(
                new ScoringEngine(500, 5, base.engine().factions()), List.of(4), base.rates(), 1_000, 1);

        var ending = ScoringEvaluator.evaluate(model).endings().getFirst();

        assertThat(ending.finalEraShare()).isEqualTo(1.0);
        assertThat(ending.meanEras()).isEqualTo(5.0);
    }

    @Test
    void equalModelsGiveEqualEvaluationsAndADifferentSeedDoesNot() {
        var model = symmetric(Faction.ERASERS, 0.6, 0.3);

        var first = ScoringEvaluator.evaluate(model);
        var again = ScoringEvaluator.evaluate(model);
        var other = ScoringEvaluator.evaluate(
                new ScoringModel(model.engine(), model.playerCounts(), model.rates(), 20_000, 8));

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void theResultDoesNotDependOnTheNumberOfThreads() throws Exception {
        var model = symmetric(Faction.ERASERS, 0.6, 0.3);
        var parallel = ScoringEvaluator.evaluate(model);

        var sequential = new java.util.concurrent.ForkJoinPool(1)
                .submit(() -> ScoringEvaluator.evaluate(model))
                .get();

        assertThat(sequential).isEqualTo(parallel);
    }
}
