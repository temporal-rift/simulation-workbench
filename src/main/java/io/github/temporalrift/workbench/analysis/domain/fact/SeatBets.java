package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.HashSet;
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
        var placedEras = new HashSet<Integer>();
        var scoredEras = new HashSet<Integer>();
        var wonEras = new HashSet<Integer>();
        var reasons = BetReasons.of(faction);
        for (var event : events) {
            switch (event) {
                case GameEvent.SpecialPlayed play
                when play.seat() == seat && BetReasons.places(faction, play) -> placedEras.add(play.era());
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
}
