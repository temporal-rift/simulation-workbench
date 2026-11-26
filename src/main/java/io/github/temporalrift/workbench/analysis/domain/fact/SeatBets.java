package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * How many of a seat's eras placed its faction's signature bet, how many of those recorded a win or a miss reason of
 * that bet, and how many recorded a win. An era counts once whatever the number of its plays or score records, and
 * a scored or won era counts only when the bet was placed in it, so scored never exceeds placed.
 */
record SeatBets(int placed, int scored, int won) {

    static SeatBets of(int seat, Faction faction, List<GameEvent> events) {
        var eras = SeatEras.of(seat, faction, events);
        var reasons = BetReasons.of(faction);
        var scored = 0;
        var won = 0;
        for (var era : eras.placedEras()) {
            var listed = eras.reasons(era);
            scored += listed.stream().anyMatch(reasons::isSettling) ? 1 : 0;
            won += listed.stream().anyMatch(reasons::wonBy) ? 1 : 0;
        }
        return new SeatBets(eras.placedEras().size(), scored, won);
    }
}
