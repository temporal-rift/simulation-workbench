package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * An authoritative winner by seat and faction.
 *
 * @param winType absent for the special endings
 * @param winConditions every condition the winner met, {@code SCORE_THRESHOLD} before {@code FACTION_OBJECTIVE}; when
 *     the list is not known it is {@code [winType]} for a score or objective win and empty otherwise
 */
public record Winner(int seatIndex, Faction faction, WinType winType, List<WinType> winConditions) {

    public Winner {
        winConditions = winConditions == null ? fallback(winType) : List.copyOf(winConditions);
    }

    /** A winner whose conditions are not known beyond its win type. */
    public Winner(int seatIndex, Faction faction, WinType winType) {
        this(seatIndex, faction, winType, null);
    }

    private static List<WinType> fallback(WinType winType) {
        return winType == WinType.SCORE_THRESHOLD || winType == WinType.FACTION_OBJECTIVE
                ? List.of(winType)
                : List.of();
    }
}
