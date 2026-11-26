package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase.Query;
import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase.SweepParameter;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.Escalation;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.ReasonPoints;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.SeatEraRates;
import io.github.temporalrift.workbench.analysis.domain.scoring.Sensitivity;
import io.github.temporalrift.workbench.shared.game.Faction;

class EvaluateScoringModelQueryHandlerTest {

    /** The win reason each faction's modelled bet settles with. */
    private static final Map<Faction, String> WIN = Map.of(
            Faction.ERASERS, "ANNIHILATED_OUTCOME",
            Faction.PROPHETS, "FULFILLMENT_SUCCEEDED",
            Faction.REVISIONISTS, "SECRET_OUTCOME_WON",
            Faction.WEAVERS, "CHAIN_LINK_ADDED",
            Faction.ACTIVISTS, "DECLARED_OUTCOME_WON");

    private final EvaluateScoringModelQueryHandler handler = new EvaluateScoringModelQueryHandler();

    private static ScoringEngine engine() {
        return new ScoringEngine(
                5,
                List.of(
                        new ReasonPoints("ANNIHILATED_OUTCOME", 6),
                        new ReasonPoints("FULFILLMENT_SUCCEEDED", 14),
                        new ReasonPoints("SECRET_OUTCOME_WON", 6),
                        new ReasonPoints("DECLARED_OUTCOME_WON", 6),
                        new ReasonPoints("CHAIN_LINK_MISSED", 0),
                        new ReasonPoints("CORRUPTED_OPPONENT_CARD", 2)),
                List.of(new Escalation("weaver-link", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(BetOutcome.VOIDED))));
    }

    private static Query query(List<SeatEraRates> rates, List<SweepParameter> sweep) {
        return query(engine(), rates, sweep);
    }

    private static Query query(ScoringEngine engine, List<SeatEraRates> rates, List<SweepParameter> sweep) {
        return new Query(engine, List.of(Faction.values()), List.of(3, 4, 5), rates, 1_000, 3, sweep);
    }

    private static List<SeatEraRates> rates() {
        var rates = new ArrayList<SeatEraRates>();
        for (var faction : Faction.values()) {
            for (var count : List.of(3, 4, 5)) {
                rates.add(new SeatEraRates(
                        count,
                        faction,
                        List.of(new SeatEraRates.BetReasonRate(WIN.get(faction), 0.5)),
                        0.2,
                        List.of(new SeatEraRates.OtherReason("CORRUPTED_OPPONENT_CARD", 0.5))));
            }
        }
        return rates;
    }

    private static SeatEraRates replaced(SeatEraRates item, List<SeatEraRates.BetReasonRate> bets, double voided) {
        return new SeatEraRates(item.playerCount(), item.faction(), bets, voided, item.otherReasons());
    }

    private static void assertRefused(Query query, String field, EvaluateScoringModelQueryHandler handler) {
        assertThatThrownBy(() -> handler.handle(query))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo(field));
    }

    @Test
    void aSweepHoldsTwoItemsPerCellAndStepDirection() {
        var result = handler.handle(query(rates(), List.of(new SweepParameter("maxEras", 1))));

        var cells = result.evaluation().cells().size();
        assertThat(result.sensitivity()).hasSize(2 * cells);
        assertThat(result.sensitivity().stream().map(Sensitivity::delta).distinct())
                .containsExactlyInAnyOrder(1.0, -1.0);
    }

    @Test
    void anEscalationIncrementSweepReportsBothDirectionsAndMovesTheWeavers() {
        var sweep = List.of(new SweepParameter("escalations.weaver-link.increment", 1));

        var result = handler.handle(query(rates(), sweep));

        assertThat(result.sensitivity()).hasSize(2 * result.evaluation().cells().size());
        assertThat(result.sensitivity())
                .filteredOn(item -> item.faction() == Faction.WEAVERS && item.playerCount() == 3 && item.delta() > 0)
                .allSatisfy(item -> assertThat(item.ratioChange()).isPositive());
        assertThat(handler.handle(query(rates(), sweep))).isEqualTo(result);
    }

    @Test
    void aReasonPointsSweepMovesTheFactionThatScoresIt() {
        var result =
                handler.handle(query(rates(), List.of(new SweepParameter("reasonPoints.FULFILLMENT_SUCCEEDED", 2))));

        assertThat(result.sensitivity())
                .filteredOn(item -> item.faction() == Faction.PROPHETS && item.delta() > 0)
                .allSatisfy(item -> assertThat(item.ratioChange()).isPositive());
    }

    @Test
    void aBetReasonRateSweepOnlyMovesTheCellsItCanReach() {
        var result = handler.handle(
                query(rates(), List.of(new SweepParameter("rates.4.WEAVERS.betReasons.CHAIN_LINK_ADDED", 0.1))));

        assertThat(result.sensitivity())
                .filteredOn(item -> item.playerCount() != 4)
                .allSatisfy(item -> assertThat(item.ratioChange()).isZero());
        assertThat(result.sensitivity())
                .filteredOn(item -> item.playerCount() == 4 && item.faction() == Faction.WEAVERS && item.delta() > 0)
                .allSatisfy(item -> assertThat(item.ratioChange()).isPositive());
    }

    @Test
    void aVoidedRateAndAnOtherReasonCanBeSwept() {
        var sweep = List.of(
                new SweepParameter("rates.3.ACTIVISTS.voidedRate", 0.1),
                new SweepParameter("rates.3.ACTIVISTS.otherReasons.CORRUPTED_OPPONENT_CARD", 0.5));

        var result = handler.handle(query(rates(), sweep));

        assertThat(result.sensitivity())
                .hasSize(2 * 2 * result.evaluation().cells().size());
    }

    @Test
    void equalRequestsGiveEqualResults() {
        var sweep = List.of(new SweepParameter("reasonPoints.ANNIHILATED_OUTCOME", 1));

        assertThat(handler.handle(query(rates(), sweep))).isEqualTo(handler.handle(query(rates(), sweep)));
    }

    @Test
    void ratesThatSumAboveOneAreRejectedNamingTheItem() {
        var rates = rates();
        rates.set(
                2,
                replaced(
                        rates.get(2),
                        List.of(new SeatEraRates.BetReasonRate(
                                WIN.get(rates.get(2).faction()), 0.9)),
                        0.2));

        assertRefused(query(rates, List.of()), "rates[2]", handler);
    }

    @Test
    void aBetReasonOfAnotherFactionIsRejectedNamingTheItem() {
        var rates = rates();
        rates.set(0, replaced(rates.get(0), List.of(new SeatEraRates.BetReasonRate("CHAIN_LINK_ADDED", 0.5)), 0.2));

        assertRefused(query(rates, List.of()), "rates[0].betReasons[0].scoreReason", handler);
    }

    @Test
    void aRatedReasonTheEngineDoesNotPriceIsRejectedNamingTheItem() {
        var unpriced = new ScoringEngine(5, List.of(new ReasonPoints("CHAIN_LINK_MISSED", 0)), List.of());

        assertRefused(query(unpriced, rates(), List.of()), "rates[0].betReasons[0].scoreReason", handler);
    }

    @Test
    void anOtherReasonThatBelongsToAnEscalationIsRejectedNamingTheItem() {
        // The default rates give every seat the other reason CORRUPTED_OPPONENT_CARD, which this engine escalates.
        var escalatesCorruption = new ScoringEngine(
                5,
                engine().reasonPoints().stream()
                        .filter(item -> !item.scoreReason().equals("CORRUPTED_OPPONENT_CARD"))
                        .toList(),
                List.of(
                        new Escalation("weaver-link", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(BetOutcome.VOIDED)),
                        new Escalation(
                                "corrupt-run", List.of("CORRUPTED_OPPONENT_CARD"), 1, 1, Set.of(BetOutcome.VOIDED))));

        assertThatThrownBy(() -> handler.handle(query(escalatesCorruption, rates(), List.of())))
                .isInstanceOfSatisfying(InvalidScoringModelException.class, e -> {
                    assertThat(e.field()).isEqualTo("rates[0].otherReasons[0].scoreReason");
                    assertThat(e.getMessage()).contains("belongs to an escalation");
                });
    }

    @Test
    void anOtherReasonThatSettlesABetIsRejectedNamingTheItem() {
        var rates = rates();
        var first = rates.get(0);
        rates.set(
                0,
                new SeatEraRates(
                        first.playerCount(),
                        first.faction(),
                        first.betReasons(),
                        first.voidedRate(),
                        List.of(new SeatEraRates.OtherReason("ANNIHILATED_OUTCOME", 1))));

        assertRefused(query(rates, List.of()), "rates[0].otherReasons[0].scoreReason", handler);
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
    void aPlayerCountAboveTheFactionsIsRejected() {
        var small = new Query(
                engine(),
                List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS),
                List.of(4),
                List.of(),
                1_000,
                3,
                List.of());

        assertRefused(small, "playerCounts", handler);
    }

    @Test
    void anUnknownSweepPathOrAZeroStepIsRejected() {
        assertRefused(query(rates(), List.of(new SweepParameter("nonsense", 1))), "sweep[0].parameter", handler);
        assertRefused(query(rates(), List.of(new SweepParameter("maxEras", 0))), "sweep[0].step", handler);
        assertRefused(
                query(rates(), List.of(new SweepParameter("escalations.unknown.base", 1))),
                "sweep[0].parameter",
                handler);
    }

    @Test
    void aNonIntegerStepOnAnIntegerValueIsRejected() {
        assertRefused(
                query(rates(), List.of(new SweepParameter("reasonPoints.ANNIHILATED_OUTCOME", 0.5))),
                "sweep[0].step",
                handler);
    }

    @Test
    void aStepThatLeavesTheAllowedRangeIsRejected() {
        assertRefused(query(rates(), List.of(new SweepParameter("maxEras", 5))), "sweep[0].step", handler);
        assertRefused(
                query(rates(), List.of(new SweepParameter("rates.5.WEAVERS.voidedRate", 0.4))),
                "sweep[0].step",
                handler);
        assertRefused(
                query(rates(), List.of(new SweepParameter("rates.3.ERASERS.betReasons.ANNIHILATED_OUTCOME", 0.6))),
                "sweep[0].step",
                handler);
    }

    @Test
    void anIncrementOutsideTheRangeIsRejectedNamingItEvenAtTheIntegerMinimum() {
        for (var increment : new int[] {21, -21, Integer.MIN_VALUE}) {
            var engine = new ScoringEngine(
                    5,
                    engine().reasonPoints(),
                    List.of(new Escalation(
                            "weaver-link", List.of("CHAIN_LINK_ADDED"), 6, increment, Set.of(BetOutcome.VOIDED))));

            assertRefused(query(engine, rates(), List.of()), "engine.escalations[0].increment", handler);
        }
    }

    @Test
    void anEngineThatRepeatsAReasonOrAnEscalationNameIsRejectedNamingTheValue() {
        var twice = new ScoringEngine(
                5,
                List.of(new ReasonPoints("ANNIHILATED_OUTCOME", 6), new ReasonPoints("ANNIHILATED_OUTCOME", 6)),
                List.of());
        var both = new ScoringEngine(
                5,
                List.of(new ReasonPoints("CHAIN_LINK_ADDED", 6)),
                List.of(new Escalation("weaver-link", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(BetOutcome.VOIDED))));
        var sameName = new ScoringEngine(
                5,
                List.of(new ReasonPoints("ANNIHILATED_OUTCOME", 6)),
                List.of(
                        new Escalation("run", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(BetOutcome.VOIDED)),
                        new Escalation("run", List.of("DECLARED_OUTCOME_WON"), 6, 3, Set.of(BetOutcome.VOIDED))));
        var sharedReason = new ScoringEngine(
                5,
                List.of(new ReasonPoints("ANNIHILATED_OUTCOME", 6)),
                List.of(
                        new Escalation("one", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(BetOutcome.VOIDED)),
                        new Escalation("two", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(BetOutcome.VOIDED))));

        assertRefused(query(twice, rates(), List.of()), "engine.reasonPoints[1].scoreReason", handler);
        assertRefused(query(both, rates(), List.of()), "engine.escalations[0].scoreReasons[0]", handler);
        assertRefused(query(sameName, rates(), List.of()), "engine.escalations[1].name", handler);
        assertRefused(query(sharedReason, rates(), List.of()), "engine.escalations[1].scoreReasons[0]", handler);
    }
}
