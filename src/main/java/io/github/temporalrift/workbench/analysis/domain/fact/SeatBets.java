package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * How many of a seat's eras placed its faction's signature bet, how many of those recorded a win or a miss reason of
 * that bet, and how many recorded a win. An era counts once whatever the number of its plays or score records, and
 * a scored or won era counts only when the bet was placed in it, so scored never exceeds placed.
 */
record SeatBets(int placed, int scored, int won) {

    /** The reasons that settle a faction's bet as kept or as lost. */
    private record Reasons(Set<String> wins, Set<String> misses) {

        /** One era's reasons for a seat arrive joined by commas, so every listed reason counts. */
        boolean settledBy(String joinedReasons) {
            return Arrays.stream(joinedReasons.split(","))
                    .anyMatch(reason -> wins.contains(reason) || misses.contains(reason));
        }

        boolean wonBy(String joinedReasons) {
            return Arrays.stream(joinedReasons.split(",")).anyMatch(wins::contains);
        }
    }

    private static final Map<Faction, Reasons> REASONS = Map.of(
            Faction.WEAVERS,
            new Reasons(Set.of("CHAIN_LINK_ADDED"), Set.of("CHAIN_LINK_MISSED")),
            Faction.REVISIONISTS,
            new Reasons(Set.of("SECRET_OUTCOME_WON"), Set.of("SECRET_OUTCOME_MISSED")),
            Faction.PROPHETS,
            new Reasons(
                    Set.of("EVENT_RESOLVED_AS_WRITTEN", "FULFILLMENT_SUCCEEDED"),
                    Set.of("EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN")),
            Faction.ACTIVISTS,
            new Reasons(
                    Set.of("DECLARED_OUTCOME_WON", "DECLARED_OUTCOME_WON_WITH_RALLY"),
                    Set.of("DECLARED_OUTCOME_MISSED", "DECLARED_OUTCOME_MISSED_WITH_RALLY")),
            Faction.ERASERS,
            new Reasons(Set.of("ANNIHILATED_OUTCOME"), Set.of("ANNIHILATION_WASTED")));

    static SeatBets of(int seat, Faction faction, List<GameEvent> events) {
        var placedEras = new HashSet<Integer>();
        var scoredEras = new HashSet<Integer>();
        var wonEras = new HashSet<Integer>();
        var reasons = REASONS.get(faction);
        for (var event : events) {
            switch (event) {
                case GameEvent.SpecialPlayed play
                when play.seat() == seat && places(faction, play) -> placedEras.add(play.era());
                case GameEvent.DeclarationRecorded declared
                when declared.seat() == seat && faction == Faction.ACTIVISTS -> placedEras.add(declared.era());
                case GameEvent.ScoreRecorded score
                when score.seat() == seat -> {
                    if (reasons.settledBy(score.reason())) {
                        scoredEras.add(score.era());
                    }
                    if (reasons.wonBy(score.reason())) {
                        wonEras.add(score.era());
                    }
                }
                default -> {
                    // not part of the seat's bet
                }
            }
        }
        scoredEras.retainAll(placedEras);
        wonEras.retainAll(placedEras);
        return new SeatBets(placedEras.size(), scoredEras.size(), wonEras.size());
    }

    private static boolean places(Faction faction, GameEvent.SpecialPlayed play) {
        return switch (faction) {
            case WEAVERS -> play.action() == SpecialAction.THREAD;
            case REVISIONISTS -> play.action() == SpecialAction.REWRITE;
            case PROPHETS -> play.action() == SpecialAction.FORESIGHT && play.namesOutcome();
            case ERASERS -> play.action() == SpecialAction.ANNIHILATE;
            case ACTIVISTS -> false;
        };
    }
}
