package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.RecordedGame;

/**
 * Reads a succeeded game's retained events as the bet outcome and the score reasons of every seat-era, the form the
 * re-scoring of a recorded game scores. Bet outcomes and score reasons come from the same seat-era reading the bet
 * metrics use, so a report and a re-score agree on what a voided bet is.
 */
public final class RecordedGames {

    /** The marker game-service records for a seat with no score decision in an era: no reason occurred. */
    private static final String NO_SCORE_CHANGE = "NO_SCORE_CHANGE";

    private RecordedGames() {}

    /**
     * @param configuredReasons the score reasons the run's variant configures
     * @throws InvalidScoringModelException when a seat records a reason the variant does not configure
     */
    public static RecordedGame of(AnalyzedCase analyzedCase, GameRecord gameRecord, Set<String> configuredReasons) {
        var eras = analyzedCase.outcome().eras();
        var seats = new ArrayList<List<RecordedGame.Era>>();
        for (var seat = 0; seat < analyzedCase.seatFactions().size(); seat++) {
            var seatEras = SeatEras.of(seat, analyzedCase.seatFactions().get(seat), gameRecord.events());
            var result = new ArrayList<RecordedGame.Era>();
            for (var era = 1; era <= eras; era++) {
                var reasons = seatEras.reasons(era).stream()
                        .filter(reason -> !NO_SCORE_CHANGE.equals(reason))
                        .toList();
                for (var reason : reasons) {
                    if (!configuredReasons.contains(reason)) {
                        throw new InvalidScoringModelException(
                                "engine",
                                "the run's variant configures no points for " + reason + ", which a case records");
                    }
                }
                result.add(new RecordedGame.Era(seatEras.outcome(era), reasons));
            }
            seats.add(result);
        }
        return new RecordedGame(
                analyzedCase.playerCount(),
                analyzedCase.seed(),
                analyzedCase.seatFactions(),
                analyzedCase.outcome().endReason(),
                eras,
                seats);
    }
}
