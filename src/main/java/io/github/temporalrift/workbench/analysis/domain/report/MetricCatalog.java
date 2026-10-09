package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import io.github.temporalrift.workbench.analysis.domain.fact.GameFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.SeatFacts;
import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;
import io.github.temporalrift.workbench.analysis.domain.report.MetricDefinition.Measure;
import io.github.temporalrift.workbench.analysis.domain.report.MetricDefinition.Tally;

/**
 * The metrics of analysis version 1, in report order. Game metrics count each eligible game once and appear only in
 * cohorts that pool seats; seat metrics count the cohort's seat-games.
 */
public final class MetricCatalog {

    private static final double[] QUANTILES = {0.1, 0.5, 0.9};
    private static final String GAMES = "games";
    private static final String FINDINGS_PER_GAME = "findings per game";
    private static final String CARDS_PER_SEAT_GAME = "cards per seat-game";
    private static final String CARD_ROUNDS = "card-rounds";

    private MetricCatalog() {}

    public static List<MetricDefinition> definitions(CohortKey cohort, Vocabulary vocabulary) {
        var definitions = new ArrayList<MetricDefinition>();
        if (cohort.seatIndex() == null) {
            gameMetrics(definitions, vocabulary);
        }
        factionMetrics(definitions, vocabulary.factions(cohort.playerCount()));
        cardMetrics(definitions, vocabulary);
        specialMetrics(definitions, vocabulary);
        return List.copyOf(definitions);
    }

    private static void gameMetrics(List<MetricDefinition> definitions, Vocabulary vocabulary) {
        for (var reason : EndReason.values()) {
            definitions.add(gameRate(
                    "ending_cause_rate", Dimensions.of(reason), GAMES, game -> game.endReason() == reason ? 1 : 0));
        }
        definitions.add(gameRate("no_winner_rate", Dimensions.NONE, GAMES, game -> game.winners() == 0 ? 1 : 0));
        definitions.add(gameRate("shared_win_rate", Dimensions.NONE, GAMES, game -> game.winners() > 1 ? 1 : 0));
        distribution(definitions, "eras", "eras", GameFacts::eras);
        distribution(definitions, "rounds", "rounds", GameFacts::rounds);
        distribution(definitions, "decisions", "decisions", GameFacts::decisions);
        definitions.add(gameMean("paradox_findings", Dimensions.NONE, FINDINGS_PER_GAME, GameFacts::totalFindings));
        for (var type : vocabulary.paradoxTypes()) {
            definitions.add(gameMean(
                    "paradox_findings",
                    Dimensions.of(type),
                    FINDINGS_PER_GAME,
                    game -> game.findings().getOrDefault(type, 0)));
        }
        definitions.add(
                gameMean("paradoxes_resolved", Dimensions.NONE, FINDINGS_PER_GAME, GameFacts::resolvedFindings));
        definitions.add(
                gameMean("paradoxes_cascaded", Dimensions.NONE, FINDINGS_PER_GAME, GameFacts::cascadedFindings));
        definitions.add(gameMean(
                "distinct_cascaded_events", Dimensions.NONE, "events per game", GameFacts::distinctCascadedEvents));
        definitions.add(ratio(
                "paradox_resolution_rate",
                MetricStatistic.RATE,
                Dimensions.NONE,
                "findings",
                (facts, seats) ->
                        Tally.of(facts.game().resolvedFindings(), facts.game().totalFindings())));
        definitions.add(ratio(
                "paradox_cascade_rate",
                MetricStatistic.RATE,
                Dimensions.NONE,
                "findings",
                (facts, seats) ->
                        Tally.of(facts.game().cascadedFindings(), facts.game().totalFindings())));
    }

    private static void factionMetrics(List<MetricDefinition> definitions, Iterable<Faction> factions) {
        for (var faction : factions) {
            var dimensions = Dimensions.of(faction);
            definitions.add(seatRate("faction_win_rate", faction, SeatFacts::won));
            definitions.add(seatRate("faction_shared_win_rate", faction, SeatFacts::sharedWin));
            definitions.add(ratio("score", MetricStatistic.MEAN, dimensions, "points", (facts, seats) -> {
                var of =
                        seats.stream().filter(seat -> seat.faction() == faction).toList();
                return Tally.of(of.stream().mapToLong(SeatFacts::score).sum(), of.size());
            }));
            for (var quantile : QUANTILES) {
                definitions.add(new MetricDefinition(
                        "score",
                        MetricStatistic.QUANTILE,
                        dimensions.at(quantile),
                        "points",
                        new Measure.Values((facts, seats, sink) -> seats.stream()
                                .filter(seat -> seat.faction() == faction)
                                .forEach(seat -> sink.accept(seat.score())))));
            }
        }
    }

    private static void cardMetrics(List<MetricDefinition> definitions, Vocabulary vocabulary) {
        for (var card : vocabulary.cards()) {
            var dimensions = Dimensions.of(card);
            definitions.add(cardMean("cards_offered", card, SeatFacts::cardsOffered));
            definitions.add(cardMean("cards_kept", card, SeatFacts::cardsKept));
            definitions.add(cardMean("cards_played", card, SeatFacts::cardsPlayed));
            definitions.add(cardMean("reactive_cards_offered", card, SeatFacts::reactiveCardsOffered));
            definitions.add(cardMean("reactive_cards_played", card, SeatFacts::reactiveCardsPlayed));
            definitions.add(ratio(
                    "card_keep_rate",
                    MetricStatistic.RATE,
                    dimensions,
                    "cards",
                    (facts, seats) -> Tally.of(
                            sum(seats, SeatFacts::cardsKept, card), sum(seats, SeatFacts::cardsOffered, card))));
            definitions.add(ratio(
                    "card_playable_rate",
                    MetricStatistic.RATE,
                    dimensions,
                    CARD_ROUNDS,
                    (facts, seats) -> new Tally(
                            sum(seats, SeatFacts::playableCardRounds, card),
                            sum(seats, SeatFacts::knownCardRounds, card),
                            sum(seats, SeatFacts::unknownCardRounds, card))));
            definitions.add(ratio(
                    "card_play_rate_when_playable",
                    MetricStatistic.RATE,
                    dimensions,
                    CARD_ROUNDS,
                    (facts, seats) -> new Tally(
                            sum(seats, SeatFacts::cardsPlayed, card),
                            sum(seats, SeatFacts::playableCardRounds, card),
                            sum(seats, SeatFacts::unknownCardRounds, card))));
        }
    }

    private static void specialMetrics(List<MetricDefinition> definitions, Vocabulary vocabulary) {
        for (var special : vocabulary.specials()) {
            definitions.add(specialMean("special_attempts", special, "submissions", SeatFacts::specialAttempts));
            definitions.add(specialMean(
                    "special_submission_rejections", special, "rejections", SeatFacts::specialSubmissionRejections));
            definitions.add(specialMean("special_accepts", special, "plays", SeatFacts::specialAccepts));
            definitions.add(specialMean(
                    "special_resolution_rejections", special, "rejections", SeatFacts::specialResolutionRejections));
        }
        definitions.add(ratio(
                "declaration_offers",
                MetricStatistic.MEAN,
                Dimensions.NONE,
                "offers per seat-game",
                (facts, seats) -> Tally.of(
                        seats.stream().mapToLong(SeatFacts::declarationOffers).sum(), seats.size())));
        for (var mode : vocabulary.declarationModes()) {
            definitions.add(specialMean("declarations", mode, "declarations", SeatFacts::declarations));
        }
        definitions.add(ratio(
                "declaration_rate",
                MetricStatistic.RATE,
                Dimensions.NONE,
                "offers",
                (facts, seats) -> Tally.of(
                        seats.stream()
                                .mapToLong(seat -> seat.declarations().values().stream()
                                        .mapToInt(Integer::intValue)
                                        .sum())
                                .sum(),
                        seats.stream().mapToLong(SeatFacts::declarationOffers).sum())));
    }

    private static MetricDefinition gameRate(
            String name, Dimensions dimensions, String unit, ToIntFunction<GameFacts> counted) {
        return ratio(
                name,
                MetricStatistic.RATE,
                dimensions,
                unit,
                (facts, seats) -> Tally.of(counted.applyAsInt(facts.game()), 1));
    }

    private static MetricDefinition gameMean(
            String name, Dimensions dimensions, String unit, ToIntFunction<GameFacts> value) {
        return ratio(
                name,
                MetricStatistic.MEAN,
                dimensions,
                unit,
                (facts, seats) -> Tally.of(value.applyAsInt(facts.game()), 1));
    }

    private static void distribution(
            List<MetricDefinition> definitions, String name, String unit, ToIntFunction<GameFacts> value) {
        definitions.add(gameMean(name, Dimensions.NONE, unit, value));
        for (var quantile : QUANTILES) {
            definitions.add(new MetricDefinition(
                    name,
                    MetricStatistic.QUANTILE,
                    Dimensions.NONE.at(quantile),
                    unit,
                    new Measure.Values((facts, seats, sink) -> sink.accept(value.applyAsInt(facts.game())))));
        }
    }

    private static MetricDefinition seatRate(String name, Faction faction, Predicate<SeatFacts> counted) {
        return ratio(name, MetricStatistic.RATE, Dimensions.of(faction), GAMES, (facts, seats) -> {
            var of = seats.stream().filter(seat -> seat.faction() == faction).toList();
            return Tally.of(of.stream().filter(counted).count(), of.size());
        });
    }

    private static MetricDefinition cardMean(
            String name, CardKey card, Function<SeatFacts, Map<CardKey, Integer>> counts) {
        return ratio(
                name,
                MetricStatistic.MEAN,
                Dimensions.of(card),
                CARDS_PER_SEAT_GAME,
                (facts, seats) -> Tally.of(sum(seats, counts, card), seats.size()));
    }

    private static MetricDefinition specialMean(
            String name, SpecialAction special, String noun, Function<SeatFacts, Map<SpecialAction, Integer>> counts) {
        return ratio(
                name,
                MetricStatistic.MEAN,
                Dimensions.of(special),
                noun + " per seat-game",
                (facts, seats) -> Tally.of(sum(seats, counts, special), seats.size()));
    }

    private static <K> long sum(List<SeatFacts> seats, Function<SeatFacts, Map<K, Integer>> counts, K key) {
        return seats.stream()
                .mapToLong(seat -> counts.apply(seat).getOrDefault(key, 0))
                .sum();
    }

    private static MetricDefinition ratio(
            String name,
            MetricStatistic statistic,
            Dimensions dimensions,
            String unit,
            MetricDefinition.RatioFunction function) {
        return new MetricDefinition(name, statistic, dimensions, unit, new Measure.Ratio(function));
    }
}
