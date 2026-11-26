package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.RecordedGame;
import io.github.temporalrift.workbench.analysis.domain.scoring.SeatScore;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Reads a succeeded game's retained events as the bet outcome and the other recorded score of every seat-era.
 *
 * <p>A bet is placed in the eras the bet metrics count as placed. Its outcome is {@code WON} when a win reason of its
 * faction is listed in the era's score records, {@code MISSED} when only a miss reason is, and {@code VOIDED} when
 * neither is; an era without a placed bet is {@code NOT_PLACED}. A score record holds one delta for all its joined
 * reasons, so the points of the bet's own reasons, as the variant configures them, are taken off it and the rest is the
 * seat-era's other score. A bet reason listed in an era without a placed bet settles nothing, so its points stay in
 * the other score.
 */
public final class RecordedGames {

    private RecordedGames() {}

    /**
     * @param scoreDeltas the variant's configured points per score reason
     * @throws IllegalStateException when a bet reason listed in a placed era has no configured points
     */
    public static RecordedGame of(AnalyzedCase analyzedCase, GameRecord gameRecord, Map<String, Integer> scoreDeltas) {
        var eras = analyzedCase.outcome().eras();
        var seats = new ArrayList<List<RecordedGame.Era>>();
        for (var seat = 0; seat < analyzedCase.seatFactions().size(); seat++) {
            seats.add(seat(seat, analyzedCase.seatFactions().get(seat), eras, gameRecord.events(), scoreDeltas));
        }
        return new RecordedGame(
                analyzedCase.playerCount(),
                analyzedCase.seed(),
                analyzedCase.seatFactions(),
                analyzedCase.outcome().endReason(),
                eras,
                seats);
    }

    private static List<RecordedGame.Era> seat(
            int seat, Faction faction, int eras, List<GameEvent> events, Map<String, Integer> scoreDeltas) {
        var placed = new HashSet<Integer>();
        var recordedPoints = new long[eras + 1];
        var reasonsByEra = new ArrayList<List<String>>();
        for (var era = 0; era <= eras; era++) {
            reasonsByEra.add(new ArrayList<>());
        }
        for (var event : events) {
            switch (event) {
                case GameEvent.SpecialPlayed play
                when play.seat() == seat && BetReasons.places(faction, play) -> placed.add(play.era());
                case GameEvent.DeclarationRecorded declared
                when declared.seat() == seat && faction == Faction.ACTIVISTS -> placed.add(declared.era());
                case GameEvent.ScoreRecorded score
                when score.seat() == seat && score.era() >= 1 && score.era() <= eras -> {
                    recordedPoints[score.era()] += score.delta();
                    reasonsByEra
                            .get(score.era())
                            .addAll(Arrays.asList(score.reason().split(",")));
                }
                default -> {
                    // not part of the seat's scoring
                }
            }
        }
        var reasons = BetReasons.of(faction);
        var result = new ArrayList<RecordedGame.Era>();
        for (var era = 1; era <= eras; era++) {
            result.add(era(placed.contains(era), reasons, reasonsByEra.get(era), recordedPoints[era], scoreDeltas));
        }
        return result;
    }

    private static RecordedGame.Era era(
            boolean placed, BetReasons reasons, List<String> listed, long recorded, Map<String, Integer> scoreDeltas) {
        if (!placed) {
            return new RecordedGame.Era(BetOutcome.NOT_PLACED, recorded * SeatScore.UNITS);
        }
        var settling = listed.stream().filter(reasons::isSettling).toList();
        var betPoints = 0L;
        for (var reason : settling) {
            var points = scoreDeltas.get(reason);
            if (points == null) {
                throw new IllegalStateException("the variant's rules have no points for " + reason);
            }
            betPoints += points;
        }
        var outcome = BetOutcome.VOIDED;
        if (listed.stream().anyMatch(reasons::wonBy)) {
            outcome = BetOutcome.WON;
        } else if (!settling.isEmpty()) {
            outcome = BetOutcome.MISSED;
        }
        return new RecordedGame.Era(outcome, (recorded - betPoints) * SeatScore.UNITS);
    }
}
