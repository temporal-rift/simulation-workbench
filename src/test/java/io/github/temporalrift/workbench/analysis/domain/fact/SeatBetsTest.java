package io.github.temporalrift.workbench.analysis.domain.fact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class SeatBetsTest {

    private static final int SEAT = 0;

    @Test
    void rewritesThatWereVoidedArePlacedAndNotScored() {
        var events = Stream.concat(
                        IntStream.rangeClosed(1, 5).mapToObj(era -> played(era, SpecialAction.REWRITE, false)),
                        Stream.of(
                                score(1, "SECRET_OUTCOME_WON", 5),
                                score(2, "SECRET_OUTCOME_WON", 5),
                                score(3, "SECRET_OUTCOME_MISSED", -2)))
                .toList();

        var bets = SeatBets.of(SEAT, Faction.REVISIONISTS, events);

        assertThat(bets).isEqualTo(new SeatBets(5, 3, 2));
    }

    @Test
    void everyReasonOfAJoinedScoreRecordCounts() {
        var events =
                List.of(played(1, SpecialAction.THREAD, false), score(1, "CHAIN_LINK_ADDED,PARADOX_STABILIZED", 6));

        assertThat(SeatBets.of(SEAT, Faction.WEAVERS, events)).isEqualTo(new SeatBets(1, 1, 1));
    }

    @Test
    void aWastedAnnihilateAloneAtZeroPointsIsScoredAndLost() {
        var events = List.of(played(1, SpecialAction.ANNIHILATE, false), score(1, "ANNIHILATION_WASTED", 0));

        assertThat(SeatBets.of(SEAT, Faction.ERASERS, events)).isEqualTo(new SeatBets(1, 1, 0));
    }

    @Test
    void aWastedAnnihilateIsScoredAndLostWhateverItsPoints() {
        var events = List.of(
                played(1, SpecialAction.ANNIHILATE, false),
                score(1, "ANNIHILATION_WASTED,PARADOX_CASCADE_PENALTY", -3));

        assertThat(SeatBets.of(SEAT, Faction.ERASERS, events)).isEqualTo(new SeatBets(1, 1, 0));
    }

    @Test
    void anAnnihilatedOutcomeIsAScoredAndWonBet() {
        var events = List.of(played(1, SpecialAction.ANNIHILATE, false), score(1, "ANNIHILATED_OUTCOME", 4));

        assertThat(SeatBets.of(SEAT, Faction.ERASERS, events)).isEqualTo(new SeatBets(1, 1, 1));
    }

    @Test
    void anAnnihilateWithNoEraserReasonIsPlacedAndNotScored() {
        var events = List.of(played(1, SpecialAction.ANNIHILATE, false), score(1, "PARADOX_CASCADE_PENALTY", -3));

        assertThat(SeatBets.of(SEAT, Faction.ERASERS, events)).isEqualTo(new SeatBets(1, 0, 0));
    }

    @Test
    void aForesightCountsOnlyWhenItNamedAnOutcome() {
        var unnamed = List.of(played(1, SpecialAction.FORESIGHT, false), score(1, "EVENT_RESOLVED_AS_WRITTEN", 4));
        var named = List.of(
                played(1, SpecialAction.FORESIGHT, true),
                played(1, SpecialAction.FORESIGHT, true),
                score(1, "FULFILLMENT_SUCCEEDED", 8));

        assertThat(SeatBets.of(SEAT, Faction.PROPHETS, unnamed)).isEqualTo(new SeatBets(0, 0, 0));
        assertThat(SeatBets.of(SEAT, Faction.PROPHETS, named)).isEqualTo(new SeatBets(1, 1, 1));
    }

    @Test
    void aProphetBetThatWrittenOutcomeMissedIsScoredAndLost() {
        var events = List.of(
                played(1, SpecialAction.FORESIGHT, true), score(1, "EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN", -3));

        assertThat(SeatBets.of(SEAT, Faction.PROPHETS, events)).isEqualTo(new SeatBets(1, 1, 0));
    }

    @Test
    void anActivistDeclarationPlacesTheBet() {
        var events = List.of(
                new GameEvent.DeclarationRecorded(SEAT, 1, SpecialAction.RALLY),
                score(1, "DECLARED_OUTCOME_WON_WITH_RALLY", 7),
                new GameEvent.DeclarationRecorded(SEAT, 2, SpecialAction.MOMENTUM),
                score(2, "DECLARED_OUTCOME_MISSED", -1),
                new GameEvent.DeclarationRecorded(SEAT, 3, SpecialAction.MOMENTUM));

        assertThat(SeatBets.of(SEAT, Faction.ACTIVISTS, events)).isEqualTo(new SeatBets(3, 2, 1));
    }

    @Test
    void aScoreReasonOfAnEraWithoutABetIsNotCounted() {
        var events = List.of(score(2, "FULFILLMENT_SUCCEEDED", 8), played(1, SpecialAction.FORESIGHT, true));

        assertThat(SeatBets.of(SEAT, Faction.PROPHETS, events)).isEqualTo(new SeatBets(1, 0, 0));
    }

    @Test
    void anotherSeatsPlaysAndScoresAreNotCounted() {
        var events =
                List.of(new GameEvent.SpecialPlayed(1, 1, SpecialAction.THREAD), score(1, 1, "CHAIN_LINK_ADDED", 6));

        assertThat(SeatBets.of(SEAT, Faction.WEAVERS, events)).isEqualTo(new SeatBets(0, 0, 0));
    }

    private static GameEvent played(int era, SpecialAction action, boolean namesOutcome) {
        return new GameEvent.SpecialPlayed(SEAT, era, action, namesOutcome);
    }

    private static GameEvent score(int era, String reason, int delta) {
        return score(SEAT, era, reason, delta);
    }

    private static GameEvent score(int seat, int era, String reason, int delta) {
        return new GameEvent.ScoreRecorded(seat, era, reason, delta);
    }
}
