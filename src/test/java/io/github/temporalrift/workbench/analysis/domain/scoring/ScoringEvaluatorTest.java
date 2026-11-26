package io.github.temporalrift.workbench.analysis.domain.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.Faction;

class ScoringEvaluatorTest {

    /** One bet reason per faction, each worth 4 points. */
    private static final List<String> WIN_REASON = List.of(
            "ANNIHILATED_OUTCOME",
            "EVENT_RESOLVED_AS_WRITTEN",
            "SECRET_OUTCOME_WON",
            "CHAIN_LINK_ADDED",
            "DECLARED_OUTCOME_WON");

    private static final ScoringEngine ENGINE = engine(5);

    private static ScoringEngine engine(int maxEras) {
        return new ScoringEngine(
                maxEras,
                WIN_REASON.stream().map(reason -> new ReasonPoints(reason, 4)).toList(),
                List.of());
    }

    private static ScoringModel symmetric(Faction boosted, double boostedWin) {
        var rates = new ArrayList<SeatEraRates>();
        for (var faction : Faction.values()) {
            var reason = WIN_REASON.get(faction.ordinal() % WIN_REASON.size());
            for (var count : List.of(3, 4, 5)) {
                var win = faction == boosted ? boostedWin : 0.5;
                rates.add(new SeatEraRates(
                        count, faction, List.of(new SeatEraRates.BetReasonRate(reason, win)), 0, List.of()));
            }
        }
        return new ScoringModel(ENGINE, List.of(Faction.values()), List.of(3, 4, 5), rates, 20_000, 7);
    }

    /** Rates under which each faction's own reason is drawn with the given chance. */
    private static List<SeatEraRates> ratesFor(ScoringEngine engine, double rate) {
        var rates = new ArrayList<SeatEraRates>();
        for (var faction : Faction.values()) {
            var reason = engine.reasonPoints().stream()
                    .map(ReasonPoints::scoreReason)
                    .filter(name -> io.github.temporalrift.workbench.analysis.domain.fact.BetReasons.of(faction)
                            .isSettling(name))
                    .findFirst()
                    .orElseThrow();
            for (var count : List.of(3, 4, 5)) {
                rates.add(new SeatEraRates(
                        count, faction, List.of(new SeatEraRates.BetReasonRate(reason, rate)), 0, List.of()));
            }
        }
        return rates;
    }

    @Test
    void identicalFactionsWinInProportionToTheirSeats() {
        var evaluation = ScoringEvaluator.evaluate(symmetric(null, 0.5));

        assertThat(evaluation.cells())
                .hasSize(15)
                .allSatisfy(cell -> assertThat(cell.ratio()).isBetween(0.95, 1.05));
    }

    @Test
    void aFactionThatSettlesItsBetWithAWinMoreOftenWinsMore() {
        var evaluation = ScoringEvaluator.evaluate(symmetric(Faction.WEAVERS, 0.9));

        assertThat(evaluation.cells())
                .filteredOn(cell -> cell.faction() == Faction.WEAVERS)
                .hasSize(3)
                .allSatisfy(cell -> assertThat(cell.ratio()).isGreaterThan(1.1));
    }

    @Test
    void theIntervalBracketsTheRatio() {
        var evaluation = ScoringEvaluator.evaluate(symmetric(null, 0.5));

        assertThat(evaluation.cells()).isNotEmpty().allSatisfy(cell -> {
            assertThat(cell.lower()).isLessThan(cell.ratio());
            assertThat(cell.upper()).isGreaterThan(cell.ratio());
        });
    }

    @Test
    void seatsThatAlwaysScoreTheSameShareTheWinEqually() {
        var model =
                new ScoringModel(ENGINE, List.of(Faction.values()), List.of(3, 4, 5), ratesFor(ENGINE, 1.0), 500, 3);

        var evaluation = ScoringEvaluator.evaluate(model);

        assertThat(evaluation.cells())
                .hasSize(15)
                .allSatisfy(cell -> assertThat(cell.ratio()).isEqualTo(1.0));
    }

    @Test
    void aCellIsListedForEveryRequestedFactionAndPlayerCount() {
        var base = symmetric(null, 0.5);
        var model = new ScoringModel(
                ENGINE,
                List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS),
                List.of(3),
                base.rates().stream()
                        .filter(item -> item.playerCount() == 3
                                && Set.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS)
                                        .contains(item.faction()))
                        .toList(),
                1_000,
                1);

        var evaluation = ScoringEvaluator.evaluate(model);

        assertThat(evaluation.cells())
                .extracting(ScoringEvaluation.Cell::faction)
                .containsExactly(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS);
    }

    @Test
    void anOtherReasonAddsItsExpectedPointsEveryEra() {
        var trio = List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS);
        var priced = new ScoringEngine(5, List.of(new ReasonPoints("CORRUPTED_OPPONENT_CARD", 2)), List.of());
        var rates = new ArrayList<SeatEraRates>();
        for (var faction : trio) {
            var others = faction == Faction.WEAVERS
                    ? List.of(new SeatEraRates.OtherReason("CORRUPTED_OPPONENT_CARD", 1))
                    : List.<SeatEraRates.OtherReason>of();
            rates.add(new SeatEraRates(3, faction, List.of(), 0, others));
        }

        var evaluation = ScoringEvaluator.evaluate(new ScoringModel(priced, trio, List.of(3), rates, 1_000, 5));

        // Five eras at an expected 2 points give the Weavers 10 and the others 0, so the Weavers win every game.
        assertThat(evaluation.cells())
                .filteredOn(cell -> cell.faction() == Faction.WEAVERS)
                .singleElement()
                .satisfies(cell -> assertThat(cell.ratio()).isEqualTo(3.0));
    }

    @Test
    void equalModelsGiveEqualEvaluationsAndADifferentSeedDoesNot() {
        var model = symmetric(Faction.ERASERS, 0.8);

        var first = ScoringEvaluator.evaluate(model);
        var again = ScoringEvaluator.evaluate(model);
        var other = ScoringEvaluator.evaluate(
                new ScoringModel(model.engine(), model.factions(), model.playerCounts(), model.rates(), 20_000, 8));

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void theResultDoesNotDependOnTheNumberOfThreads() throws Exception {
        var model = symmetric(Faction.ERASERS, 0.8);
        var parallel = ScoringEvaluator.evaluate(model);

        var sequential = new java.util.concurrent.ForkJoinPool(1)
                .submit(() -> ScoringEvaluator.evaluate(model))
                .get();

        assertThat(sequential).isEqualTo(parallel);
    }
}
