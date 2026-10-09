package io.github.temporalrift.workbench.analysis.domain.fact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.game.CardGrade;
import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.CardType;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;

class FactExtractorTest {

    private static final UUID CASE = UUID.fromString("00000000-0000-0000-0000-0000000000ca");
    private static final List<Faction> SEATS = List.of(Faction.ERASERS, Faction.WEAVERS, Faction.PROPHETS);
    private static final CardKey SCAN_II = new CardKey(CardType.SCAN, CardGrade.II);
    private static final CardKey PUSH_I = new CardKey(CardType.PUSH, CardGrade.I);

    @Test
    void aSharedWinCountsForEveryWinner() {
        var facts = FactExtractor.extract(CASE, SEATS, outcome(Set.of(0, 1)), empty());

        assertThat(facts.seats()).extracting(SeatFacts::won).containsExactly(true, true, false);
        assertThat(facts.seats()).extracting(SeatFacts::sharedWin).containsExactly(true, true, false);
        assertThat(facts.seats()).extracting(SeatFacts::score).containsExactly(22, 22, 9);
        assertThat(facts.game().winners()).isEqualTo(2);
    }

    @Test
    void findingsAndCascadedEventsAreCountedInTheirOwnUnits() {
        var first = id(1);
        var second = id(2);
        var third = id(3);
        var event = id(9);
        var record = new GameRecord(
                List.of(
                        new GameEvent.ParadoxesDetected(
                                1,
                                List.of(
                                        new GameEvent.Finding(first, ParadoxType.DEAD_HEAT),
                                        new GameEvent.Finding(second, ParadoxType.DEAD_HEAT),
                                        new GameEvent.Finding(third, ParadoxType.CHAIN_CONFLICT))),
                        new GameEvent.ParadoxCascaded(List.of(first), event),
                        new GameEvent.ParadoxCascaded(List.of(second), event),
                        new GameEvent.ParadoxResolved(third)),
                List.of(),
                List.of());

        var game =
                FactExtractor.extract(CASE, SEATS, outcome(Set.of(0)), record).game();

        assertThat(game.totalFindings()).isEqualTo(3);
        assertThat(game.findings()).isEqualTo(Map.of(ParadoxType.DEAD_HEAT, 2, ParadoxType.CHAIN_CONFLICT, 1));
        assertThat(game.cascadedFindings()).isEqualTo(2);
        assertThat(game.distinctCascadedEvents()).isEqualTo(1);
        assertThat(game.resolvedFindings()).isEqualTo(1);
    }

    @Test
    void anUnobservedRoundCountsItsHeldCardsAsUnknownPlayability() {
        var scan = id(10);
        var push = id(11);
        var record = new GameRecord(
                List.of(
                        new GameEvent.HandKept(
                                0, 1, List.of(new GameEvent.Card(scan, SCAN_II), new GameEvent.Card(push, PUSH_I))),
                        new GameEvent.ActionRoundStarted(1, 1),
                        new GameEvent.CardPlayed(0, 1, 1, push, PUSH_I),
                        new GameEvent.ActionRoundStarted(1, 2),
                        new GameEvent.ActionRoundStarted(1, 3),
                        new GameEvent.ActionRoundStarted(1, 4)),
                List.of(
                        observed(1, List.of(held(scan, SCAN_II, true), held(push, PUSH_I, true))),
                        observed(2, List.of(held(scan, SCAN_II, false))),
                        observed(3, List.of(held(scan, SCAN_II, true)))),
                List.of());

        var seat = FactExtractor.extract(CASE, SEATS, outcome(Set.of(0)), record)
                .seats()
                .getFirst();

        assertThat(seat.knownCardRounds()).containsEntry(SCAN_II, 3).containsEntry(PUSH_I, 1);
        assertThat(seat.playableCardRounds()).containsEntry(SCAN_II, 2).containsEntry(PUSH_I, 1);
        assertThat(seat.unknownCardRounds()).containsExactlyEntriesOf(Map.of(SCAN_II, 1));
        assertThat(seat.cardsKept()).containsEntry(SCAN_II, 1).containsEntry(PUSH_I, 1);
        assertThat(seat.cardsPlayed()).containsExactlyEntriesOf(Map.of(PUSH_I, 1));
    }

    @Test
    void specialsAndDeclarationsAreCountedPerSeatAndSource() {
        var record = new GameRecord(
                List.of(
                        new GameEvent.SpecialPlayed(1, 1, SpecialAction.THREAD),
                        new GameEvent.SpecialRejected(1, 1, SpecialAction.THREAD),
                        new GameEvent.DeclarationOffered(2, 1),
                        new GameEvent.DeclarationRecorded(2, 1, SpecialAction.RALLY),
                        new GameEvent.HandDealt(0, 1, List.of(SCAN_II, SCAN_II, PUSH_I))),
                List.of(),
                List.of(
                        new GameRecord.SpecialSubmission(1, SpecialAction.THREAD, true),
                        new GameRecord.SpecialSubmission(1, SpecialAction.THREAD, false)));

        var seats =
                FactExtractor.extract(CASE, SEATS, outcome(Set.of(0)), record).seats();

        assertThat(seats.get(1).specialAttempts()).containsExactlyEntriesOf(Map.of(SpecialAction.THREAD, 2));
        assertThat(seats.get(1).specialSubmissionRejections())
                .containsExactlyEntriesOf(Map.of(SpecialAction.THREAD, 1));
        assertThat(seats.get(1).specialAccepts()).containsExactlyEntriesOf(Map.of(SpecialAction.THREAD, 1));
        assertThat(seats.get(1).specialResolutionRejections())
                .containsExactlyEntriesOf(Map.of(SpecialAction.THREAD, 1));
        assertThat(seats.get(2).declarationOffers()).isEqualTo(1);
        assertThat(seats.get(2).declarations()).containsExactlyEntriesOf(Map.of(SpecialAction.RALLY, 1));
        assertThat(seats.get(0).cardsOffered()).containsEntry(SCAN_II, 2).containsEntry(PUSH_I, 1);
        assertThat(seats.get(0).specialAttempts()).isEmpty();
    }

    private static GameRecord.RoundObservation observed(int round, List<GameRecord.HeldCard> hand) {
        return new GameRecord.RoundObservation(0, 1, round, hand);
    }

    private static GameRecord.HeldCard held(UUID id, CardKey key, boolean playable) {
        return new GameRecord.HeldCard(id, key, playable);
    }

    private static CaseOutcome outcome(Set<Integer> winners) {
        return new CaseOutcome(EndReason.WIN_CONDITION_MET, winners, List.of(22, 22, 9), 3, 9, 41);
    }

    private static GameRecord empty() {
        return new GameRecord(List.of(), List.of(), List.of());
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }
}
