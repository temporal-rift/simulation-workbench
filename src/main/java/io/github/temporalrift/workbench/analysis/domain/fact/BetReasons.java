package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * The score reasons that settle each faction's signature bet as kept or as lost, and the plays that place it. The bet
 * metrics and the re-scoring of a recorded game share this one definition.
 */
public final class BetReasons {

    private static final Map<Faction, BetReasons> BY_FACTION = Map.of(
            Faction.WEAVERS,
            new BetReasons(Set.of("CHAIN_LINK_ADDED"), Set.of("CHAIN_LINK_MISSED")),
            Faction.REVISIONISTS,
            new BetReasons(Set.of("SECRET_OUTCOME_WON"), Set.of("SECRET_OUTCOME_MISSED")),
            Faction.PROPHETS,
            new BetReasons(
                    Set.of("EVENT_RESOLVED_AS_WRITTEN", "FULFILLMENT_SUCCEEDED"),
                    Set.of("EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN")),
            Faction.ACTIVISTS,
            new BetReasons(
                    Set.of("DECLARED_OUTCOME_WON", "DECLARED_OUTCOME_WON_WITH_RALLY"),
                    Set.of("DECLARED_OUTCOME_MISSED", "DECLARED_OUTCOME_MISSED_WITH_RALLY")),
            Faction.ERASERS,
            new BetReasons(Set.of("ANNIHILATED_OUTCOME"), Set.of("ANNIHILATION_WASTED")));

    private final Set<String> wins;
    private final Set<String> misses;

    private BetReasons(Set<String> wins, Set<String> misses) {
        this.wins = wins;
        this.misses = misses;
    }

    public static BetReasons of(Faction faction) {
        return BY_FACTION.get(faction);
    }

    /** Whether a play places the faction's bet. */
    public static boolean places(Faction faction, GameEvent.SpecialPlayed play) {
        return switch (faction) {
            case WEAVERS -> play.action() == SpecialAction.THREAD;
            case REVISIONISTS -> play.action() == SpecialAction.REWRITE;
            case PROPHETS -> play.action() == SpecialAction.FORESIGHT && play.namesOutcome();
            case ERASERS -> play.action() == SpecialAction.ANNIHILATE;
            case ACTIVISTS -> false;
        };
    }

    public boolean wonBy(String joinedReasons) {
        return Arrays.stream(joinedReasons.split(",")).anyMatch(wins::contains);
    }

    /** Whether the reason is one of the faction's bet reasons, kept or lost. */
    public boolean isSettling(String reason) {
        return wins.contains(reason) || misses.contains(reason);
    }

    /** Whether the reason settles any faction's bet, kept or lost, and is scored by a scoring engine. */
    public static boolean isBetReason(String reason) {
        return BY_FACTION.values().stream().anyMatch(reasons -> reasons.isSettling(reason));
    }
}
