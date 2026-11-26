package io.github.temporalrift.workbench.analysis.domain.fact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.RecordedGame;
import io.github.temporalrift.workbench.analysis.domain.scoring.SeatScore;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class RecordedGamesTest {

    private static final Map<String, Integer> DELTAS = Map.of(
            "CHAIN_LINK_ADDED", 3,
            "CHAIN_LINK_MISSED", -1,
            "PARADOX_CASCADE_PENALTY", -2,
            "ANNIHILATED_OUTCOME", 6,
            "ANNIHILATION_WASTED", -1);

    private static RecordedGame read(List<Faction> factions, int eras, GameEvent... events) {
        var outcome = new CaseOutcome(EndReason.TIMELINE_STABILIZED, Set.of(0), List.of(0, 0, 0), eras, 0, 0);
        var analyzed = new AnalyzedCase(
                UUID.randomUUID(), "v", "t", "7", factions.size(), List.of(), factions, CaseState.SUCCEEDED, outcome);
        return RecordedGames.of(analyzed, new GameRecord(List.of(events), List.of(), List.of()), DELTAS);
    }

    private static GameEvent thread(int era) {
        return new GameEvent.SpecialPlayed(0, era, SpecialAction.THREAD);
    }

    private static GameEvent score(int seat, int era, String reason, int delta) {
        return new GameEvent.ScoreRecorded(seat, era, reason, delta);
    }

    private static final List<Faction> TABLE = List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS);

    @Test
    void aWeaversBetOutcomesFollowTheListedReasonsOfEachEra() {
        var game = read(
                TABLE,
                5,
                thread(1),
                thread(2),
                thread(3),
                thread(4),
                score(0, 1, "CHAIN_LINK_ADDED", 3),
                score(0, 2, "CHAIN_LINK_ADDED", 3),
                score(0, 3, "CHAIN_LINK_MISSED", -1),
                score(0, 4, "CHAIN_LINK_ADDED,PARADOX_CASCADE_PENALTY", 1));

        assertThat(game.seats().get(0).stream().map(RecordedGame.Era::outcome))
                .containsExactly(
                        BetOutcome.WON, BetOutcome.WON, BetOutcome.MISSED, BetOutcome.WON, BetOutcome.NOT_PLACED);
    }

    @Test
    void theOtherScoreOfAJoinedRecordIsWhatTheBetsOwnReasonsDoNotAccountFor() {
        var game = read(
                TABLE,
                5,
                thread(1),
                thread(4),
                score(0, 1, "CHAIN_LINK_ADDED", 3),
                score(0, 4, "CHAIN_LINK_ADDED,PARADOX_CASCADE_PENALTY", 1),
                score(0, 5, "PARADOX_CASCADE_PENALTY", -2));

        assertThat(game.seats().get(0).stream().map(era -> era.otherUnits() / SeatScore.UNITS))
                .containsExactly(0L, 0L, 0L, -2L, -2L);
    }

    @Test
    void aBetPlacedWithoutAnyListedReasonIsVoided() {
        var game = read(TABLE, 2, thread(1));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.VOIDED);
        assertThat(game.seats().get(0).get(1).outcome()).isEqualTo(BetOutcome.NOT_PLACED);
    }

    @Test
    void aBetReasonListedWithoutAPlacedBetSettlesNothingAndKeepsItsPoints() {
        var game = read(TABLE, 1, score(0, 1, "CHAIN_LINK_ADDED", 3));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.NOT_PLACED);
        assertThat(game.seats().get(0).get(0).otherUnits()).isEqualTo(3 * SeatScore.UNITS);
    }

    @Test
    void aWinReasonWinsOverAMissReasonOfTheSameEra() {
        var game = read(
                List.of(Faction.ERASERS, Faction.WEAVERS, Faction.PROPHETS),
                1,
                new GameEvent.SpecialPlayed(0, 1, SpecialAction.ANNIHILATE),
                score(0, 1, "ANNIHILATED_OUTCOME,ANNIHILATION_WASTED", 5));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.WON);
        assertThat(game.seats().get(0).get(0).otherUnits()).isZero();
    }

    @Test
    void aSeatOtherThanTheBettorsIsNotMixedIn() {
        var game = read(TABLE, 1, thread(1), score(1, 1, "CHAIN_LINK_ADDED", 3));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.VOIDED);
        assertThat(game.seats().get(1).get(0).outcome()).isEqualTo(BetOutcome.NOT_PLACED);
    }

    @Test
    void aPlacedBetReasonWithoutConfiguredPointsIsRefused() {
        var table = List.of(Faction.REVISIONISTS, Faction.WEAVERS, Faction.PROPHETS);
        var rewrite = new GameEvent.SpecialPlayed(0, 1, SpecialAction.REWRITE);
        var won = score(0, 1, "SECRET_OUTCOME_WON", 4);

        assertThatThrownBy(() -> read(table, 1, rewrite, won)).isInstanceOf(IllegalStateException.class);
    }
}
