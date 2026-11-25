package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase.Query;
import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase.SweepParameter;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetRates;
import io.github.temporalrift.workbench.analysis.domain.scoring.FactionScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.shared.game.Faction;

class EvaluateScoringModelQueryHandlerTest {

    private final EvaluateScoringModelQueryHandler handler = new EvaluateScoringModelQueryHandler();

    private static Query query(List<BetRates> rates, List<SweepParameter> sweep) {
        var factions = new ArrayList<FactionScoring>();
        for (var faction : Faction.values()) {
            factions.add(new FactionScoring(faction, List.of(4), 0, Set.of(), null, 0));
        }
        return new Query(new ScoringEngine(12, 5, factions), List.of(3, 4, 5), rates, 1_000, 3, sweep);
    }

    private static List<BetRates> rates() {
        return rates(0.5, 0.5);
    }

    private static List<BetRates> rates(double win, double miss) {
        var rates = new ArrayList<BetRates>();
        for (var faction : Faction.values()) {
            for (var count : List.of(3, 4, 5)) {
                rates.add(new BetRates(count, faction, win, miss, 0));
            }
        }
        return rates;
    }

    @Test
    void aSweepHoldsTwoItemsPerCellAndStepDirection() {
        var result = handler.handle(query(rates(), List.of(new SweepParameter("winThreshold", 2))));

        var cells = result.evaluation().cells().size();
        assertThat(result.sensitivity()).hasSize(2 * cells);
        assertThat(result.sensitivity().stream().map(item -> item.delta()).distinct())
                .containsExactlyInAnyOrder(2.0, -2.0);
    }

    @Test
    void aWinRateSweepOnlyMovesTheCellsItCanReach() {
        var result =
                handler.handle(query(rates(0.4, 0.4), List.of(new SweepParameter("rates.4.WEAVERS.winRate", 0.1))));

        assertThat(result.sensitivity())
                .filteredOn(item -> item.playerCount() != 4)
                .allSatisfy(item -> assertThat(item.ratioChange()).isZero());
        assertThat(result.sensitivity())
                .filteredOn(item -> item.playerCount() == 4 && item.faction() == Faction.WEAVERS && item.delta() > 0)
                .allSatisfy(item -> assertThat(item.ratioChange()).isPositive());
    }

    @Test
    void equalRequestsGiveEqualResults() {
        var sweep = List.of(new SweepParameter("factions.ERASERS.winPoints[0]", 1));

        assertThat(handler.handle(query(rates(), sweep))).isEqualTo(handler.handle(query(rates(), sweep)));
    }

    @Test
    void ratesAboveOneAreRejectedNamingTheItem() {
        var rates = rates();
        rates.set(2, new BetRates(rates.get(2).playerCount(), rates.get(2).faction(), 0.7, 0.4, 0));

        assertThatThrownBy(() -> handler.handle(query(rates, List.of())))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("rates[2]"));
    }

    @Test
    void aMissingRatesItemIsRejected() {
        var rates = rates().stream()
                .filter(item -> !(item.playerCount() == 4 && item.faction() == Faction.PROPHETS))
                .toList();

        assertThatThrownBy(() -> handler.handle(query(rates, List.of())))
                .isInstanceOfSatisfying(InvalidScoringModelException.class, e -> {
                    assertThat(e.field()).isEqualTo("rates");
                    assertThat(e.getMessage()).contains("(4, PROPHETS)");
                });
    }

    @Test
    void aPlayerCountAboveTheEngineFactionsIsRejected() {
        var base = query(rates(), List.of());
        var small = new Query(
                new ScoringEngine(12, 5, base.engine().factions().subList(0, 3)),
                List.of(4),
                List.of(),
                1_000,
                3,
                List.of());

        assertThatThrownBy(() -> handler.handle(small))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("playerCounts"));
    }

    @Test
    void anUnknownSweepPathOrAZeroStepIsRejected() {
        assertThatThrownBy(() -> handler.handle(query(rates(), List.of(new SweepParameter("nonsense", 1)))))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("sweep[0].parameter"));
        assertThatThrownBy(() -> handler.handle(query(rates(), List.of(new SweepParameter("winThreshold", 0)))))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("sweep[0].step"));
    }

    @Test
    void aStepThatLeavesTheAllowedRangeIsRejected() {
        assertThatThrownBy(() -> handler.handle(query(rates(), List.of(new SweepParameter("winThreshold", 12)))))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("sweep[0].step"));
        assertThatThrownBy(() ->
                        handler.handle(query(rates(), List.of(new SweepParameter("rates.3.ERASERS.winRate", 0.6)))))
                .isInstanceOf(InvalidScoringModelException.class);
    }

    @Test
    void anObjectiveBonusSweepNeedsAnObjective() {
        assertThatThrownBy(() -> handler.handle(
                        query(rates(), List.of(new SweepParameter("factions.ERASERS.objectiveBonus", 1)))))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("sweep[0].parameter"));
    }
}
