package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Replays a recorded game under a scoring engine, era by era: each seat-era's bet outcome is scored under the seat's
 * faction, its other recorded score is added, and the race rule decides at each era end.
 */
public final class Rescorer {

    /** The least common multiple of the possible number of sharing winners, 1 to 5. */
    public static final int SHARE_UNITS = 60;

    private final ScoringEngine engine;
    private final Map<Faction, FactionScoring> byFaction = new EnumMap<>(Faction.class);

    public Rescorer(ScoringEngine engine) {
        this.engine = engine;
        engine.factions().forEach(faction -> byFaction.put(faction.faction(), faction));
    }

    /** What the engine's race makes of a recorded game. */
    public sealed interface Verdict {

        /** The race ended in {@code era}; each seat's win share is in {@link #SHARE_UNITS}ths. */
        record Judged(int era, List<Integer> shares) implements Verdict {

            public Judged {
                shares = List.copyOf(shares);
            }
        }

        /** The race would outlast the recorded game, so it cannot be judged. */
        record Truncated() implements Verdict {}
    }

    /**
     * @param finalEra the last era of the game's arc
     * @throws IllegalArgumentException when a seat's faction has no entry in the engine
     */
    public Verdict judge(RecordedGame game, int finalEra) {
        var scores = new SeatScore[game.factions().size()];
        for (var seat = 0; seat < scores.length; seat++) {
            var scoring = byFaction.get(game.factions().get(seat));
            if (scoring == null) {
                throw new IllegalArgumentException(
                        "the engine has no entry for " + game.factions().get(seat));
            }
            scores[seat] = new SeatScore(scoring);
        }
        var threshold = engine.winThreshold() * SeatScore.UNITS;
        var last = Math.min(game.eras(), engine.maxEras());
        for (var era = 1; era <= last; era++) {
            for (var seat = 0; seat < scores.length; seat++) {
                var recorded = game.seats().get(seat).get(era - 1);
                scores[seat].apply(recorded.outcome());
                scores[seat].addExtra(recorded.otherUnits());
            }
            if (best(scores) >= threshold || era == engine.maxEras()) {
                return new Verdict.Judged(era, shares(scores));
            }
        }
        if (game.eras() >= 1 && (game.eras() == finalEra || game.endReason() == EndReason.TIMELINE_COLLAPSED)) {
            return new Verdict.Judged(game.eras(), shares(scores));
        }
        return new Verdict.Truncated();
    }

    private static long best(SeatScore[] scores) {
        var best = Long.MIN_VALUE;
        for (var score : scores) {
            best = Math.max(best, score.total());
        }
        return best;
    }

    private static List<Integer> shares(SeatScore[] scores) {
        var best = best(scores);
        var winners = 0;
        for (var score : scores) {
            winners += score.total() == best ? 1 : 0;
        }
        var shares = new int[scores.length];
        for (var seat = 0; seat < scores.length; seat++) {
            shares[seat] = scores[seat].total() == best ? SHARE_UNITS / winners : 0;
        }
        return Arrays.stream(shares).boxed().toList();
    }
}
