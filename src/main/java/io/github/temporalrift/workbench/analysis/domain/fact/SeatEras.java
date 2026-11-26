package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * What one seat did in each era, read once from a game's retained events so that the bet metrics, the reason metrics
 * and the re-scoring of a recorded game classify seat-eras alike.
 *
 * <p>A bet is placed in the eras where the faction's signature bet was placed. A seat-era's score reasons are the
 * reasons its score records list, a joined record split and a reason listed twice counted twice. Its bet outcome is
 * {@code WON} when a win reason of its faction is listed, {@code MISSED} when only a miss reason is, and
 * {@code VOIDED} when the bet was placed but neither is; an era without a placed bet is {@code NOT_PLACED}, and a bet
 * reason listed in it settles nothing.
 */
final class SeatEras {

    private final BetReasons bet;
    private final Set<Integer> placed;
    private final Map<Integer, List<String>> reasons;

    private SeatEras(BetReasons bet, Set<Integer> placed, Map<Integer, List<String>> reasons) {
        this.bet = bet;
        this.placed = placed;
        this.reasons = reasons;
    }

    static SeatEras of(int seat, Faction faction, List<GameEvent> events) {
        var placed = new HashSet<Integer>();
        var reasons = new HashMap<Integer, List<String>>();
        for (var event : events) {
            switch (event) {
                case GameEvent.SpecialPlayed play
                when play.seat() == seat && BetReasons.places(faction, play) -> placed.add(play.era());
                case GameEvent.DeclarationRecorded declared
                when declared.seat() == seat && faction == Faction.ACTIVISTS -> placed.add(declared.era());
                case GameEvent.ScoreRecorded score
                when score.seat() == seat ->
                    reasons.computeIfAbsent(score.era(), era -> new ArrayList<>())
                            .addAll(Arrays.asList(score.reason().split(",")));
                default -> {
                    // not part of the seat's bet or scoring
                }
            }
        }
        return new SeatEras(BetReasons.of(faction), placed, reasons);
    }

    /** The eras where the bet was placed. */
    Set<Integer> placedEras() {
        return placed;
    }

    /** The score reasons the seat recorded in an era, one occurrence each. */
    List<String> reasons(int era) {
        return reasons.getOrDefault(era, List.of());
    }

    /** The era's bet outcome. */
    BetOutcome outcome(int era) {
        if (!placed.contains(era)) {
            return BetOutcome.NOT_PLACED;
        }
        var listed = reasons(era);
        if (listed.stream().anyMatch(bet::wonBy)) {
            return BetOutcome.WON;
        }
        return listed.stream().anyMatch(bet::isSettling) ? BetOutcome.MISSED : BetOutcome.VOIDED;
    }

    /** The eras 1 to {@code eras} where the bet was placed and recorded no bet reason. */
    int voided(int eras) {
        var voided = 0;
        for (var era = 1; era <= eras; era++) {
            voided += outcome(era) == BetOutcome.VOIDED ? 1 : 0;
        }
        return voided;
    }

    /** How often each score reason occurred in the eras 1 to {@code eras}. */
    Map<String, Integer> occurrences(int eras) {
        var counts = new TreeMap<String, Integer>();
        for (var era = 1; era <= eras; era++) {
            reasons(era).forEach(reason -> counts.merge(reason, 1, Integer::sum));
        }
        return counts;
    }
}
