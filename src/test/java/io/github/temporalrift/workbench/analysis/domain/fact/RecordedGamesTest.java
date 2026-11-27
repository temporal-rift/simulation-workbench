package io.github.temporalrift.workbench.analysis.domain.fact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.RecordedGame;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class RecordedGamesTest {

    private static final Set<String> CONFIGURED = Set.of(
            "CHAIN_LINK_ADDED",
            "CHAIN_LINK_MISSED",
            "PARADOX_CASCADE_PENALTY",
            "ANNIHILATED_OUTCOME",
            "ANNIHILATION_WASTED",
            "ANNIHILATION_WASTED_TOO");
    private static final List<Faction> TABLE = List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS);

    private static RecordedGame read(List<Faction> factions, int eras, GameEvent... events) {
        var outcome = new CaseOutcome(EndReason.TIMELINE_STABILIZED, Set.of(0), List.of(0, 0, 0), eras, 0, 0);
        var analyzed = new AnalyzedCase(
                UUID.randomUUID(), "v", "t", "7", factions.size(), List.of(), factions, CaseState.SUCCEEDED, outcome);
        return RecordedGames.of(analyzed, new GameRecord(List.of(events), List.of(), List.of()), CONFIGURED);
    }

    private static GameEvent thread(int era) {
        return new GameEvent.SpecialPlayed(0, era, SpecialAction.THREAD);
    }

    private static GameEvent score(int seat, int era, String reason, int delta) {
        return new GameEvent.ScoreRecorded(seat, era, reason, delta);
    }

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
    void aJoinedRecordIsSplitAndARepeatedReasonCountsEachTime() {
        var game = read(
                TABLE,
                2,
                thread(1),
                score(0, 1, "CHAIN_LINK_ADDED,PARADOX_CASCADE_PENALTY", 1),
                score(0, 2, "PARADOX_CASCADE_PENALTY,PARADOX_CASCADE_PENALTY", -4));

        assertThat(game.seats().get(0).get(0).reasons()).containsExactly("CHAIN_LINK_ADDED", "PARADOX_CASCADE_PENALTY");
        assertThat(game.seats().get(0).get(1).reasons())
                .containsExactly("PARADOX_CASCADE_PENALTY", "PARADOX_CASCADE_PENALTY");
    }

    @Test
    void aBetPlacedWithoutAnyListedReasonIsVoided() {
        var game = read(TABLE, 2, thread(1));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.VOIDED);
        assertThat(game.seats().get(0).get(1).outcome()).isEqualTo(BetOutcome.NOT_PLACED);
    }

    @Test
    void aBetReasonListedWithoutAPlacedBetSettlesNothingButStillOccurs() {
        var game = read(TABLE, 1, score(0, 1, "CHAIN_LINK_ADDED", 3));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.NOT_PLACED);
        assertThat(game.seats().get(0).get(0).reasons()).containsExactly("CHAIN_LINK_ADDED");
    }

    @Test
    void aWinReasonWinsOverAMissReasonOfTheSameEra() {
        var game = read(
                List.of(Faction.ERASERS, Faction.WEAVERS, Faction.PROPHETS),
                1,
                new GameEvent.SpecialPlayed(0, 1, SpecialAction.ANNIHILATE),
                score(0, 1, "ANNIHILATED_OUTCOME,ANNIHILATION_WASTED", 5));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.WON);
    }

    @Test
    void aSeatOtherThanTheBettorsIsNotMixedIn() {
        var game = read(TABLE, 1, thread(1), score(1, 1, "CHAIN_LINK_ADDED", 3));

        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.VOIDED);
        assertThat(game.seats().get(1).get(0).outcome()).isEqualTo(BetOutcome.NOT_PLACED);
    }

    @Test
    void aRecordedReasonTheVariantDoesNotConfigureIsRefusedNamingIt() {
        var table = List.of(Faction.REVISIONISTS, Faction.WEAVERS, Faction.PROPHETS);
        var rewrite = new GameEvent.SpecialPlayed(0, 1, SpecialAction.REWRITE);
        var won = score(0, 1, "SECRET_OUTCOME_WON", 4);

        assertThatThrownBy(() -> read(table, 1, rewrite, won))
                .isInstanceOfSatisfying(InvalidScoringModelException.class, e -> {
                    assertThat(e.field()).isEqualTo("engine");
                    assertThat(e.getMessage()).contains("SECRET_OUTCOME_WON");
                });
    }

    @Test
    void theNoScoreChangeMarkerIsNotAReasonAndKeepsABetVoided() {
        var game = read(TABLE, 1, thread(1), score(0, 1, "NO_SCORE_CHANGE", 0));

        assertThat(game.seats().get(0).get(0).reasons()).isEmpty();
        assertThat(game.seats().get(0).get(0).outcome()).isEqualTo(BetOutcome.VOIDED);
    }

    @Test
    void aReasonRecordedAfterTheLastPlayedEraIsNotRead() {
        var game = read(TABLE, 1, score(0, 2, "NOT_CONFIGURED_AT_ALL", 1));

        assertThat(game.seats().get(0))
                .singleElement()
                .satisfies(era -> assertThat(era.reasons()).isEmpty());
    }

    @Test
    void theReportAndTheRescoreClassifyTheSameSeatErasAsVoided() {
        // Eras: 1 a kept link, 2 voided, 3 no bet, 4 a missed link, 5 voided.
        var events = List.<GameEvent>of(
                thread(1),
                thread(2),
                thread(4),
                thread(5),
                score(0, 1, "CHAIN_LINK_ADDED", 3),
                score(0, 4, "CHAIN_LINK_MISSED", -1));
        var game = read(TABLE, 5, events.toArray(GameEvent[]::new));
        var seatEras = SeatEras.of(0, Faction.WEAVERS, events);

        var rescoredVoided = game.seats().get(0).stream()
                .filter(era -> era.outcome() == BetOutcome.VOIDED)
                .count();

        assertThat(rescoredVoided).isEqualTo(2);
        assertThat(seatEras.voided(5)).isEqualTo((int) rescoredVoided);
    }
}
