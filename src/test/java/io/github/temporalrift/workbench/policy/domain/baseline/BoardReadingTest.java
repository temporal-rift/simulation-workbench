package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.observation.Band;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PublicBand;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

class BoardReadingTest {

    private static final UUID EVENT = PolicyFixtures.EVENT_A;
    private static final UUID LOW_PRINTED_HIGH = PolicyFixtures.id(0xA1);
    private static final UUID HIGH_BAND = PolicyFixtures.id(0xA2);
    private static final UUID UNBANDED = PolicyFixtures.id(0xA3);

    @Test
    void theBandOutranksThePrintedWeightAndNoBandCountsAsMedium() {
        var reading = BoardReading.of(observation(
                Faction.ERASERS,
                List.of(
                        new OutcomeView(LOW_PRINTED_HIGH, 70, null, new PublicBand(Band.LOW, 2)),
                        new OutcomeView(HIGH_BAND, 10, null, new PublicBand(Band.HIGH, 2)),
                        new OutcomeView(UNBANDED, 20, null)),
                List.of()));

        assertThat(reading.rank(HIGH_BAND)).isEqualTo(100);
        assertThat(reading.rank(UNBANDED)).isEqualTo(50);
        assertThat(reading.rank(LOW_PRINTED_HIGH)).isZero();
        assertThat(reading.isLow(LOW_PRINTED_HIGH)).isTrue();
        assertThat(reading.isHigh(HIGH_BAND)).isTrue();
    }

    @Test
    void equalKeysShareTheirRankAndAScanRefinesWithinABand() {
        var reading = BoardReading.of(observation(
                Faction.ERASERS,
                List.of(
                        new OutcomeView(LOW_PRINTED_HIGH, 30, null),
                        new OutcomeView(HIGH_BAND, 30, null),
                        new OutcomeView(UNBANDED, 10, 60)),
                List.of()));

        assertThat(reading.rank(LOW_PRINTED_HIGH))
                .isEqualTo(reading.rank(HIGH_BAND))
                .isZero();
        assertThat(reading.rank(UNBANDED)).isEqualTo(100);
    }

    @Test
    void eachFactionCommitsToItsOwnAction() {
        var foresight = own(1, SpecialAction.FORESIGHT, HIGH_BAND);
        var rewrite = own(1, SpecialAction.REWRITE, HIGH_BAND);
        var thread = own(1, SpecialAction.THREAD, LOW_PRINTED_HIGH);
        var reweave = own(3, SpecialAction.REWEAVE, HIGH_BAND);
        var rally = new Board.OwnSubmission(
                WindowKind.DECLARATION,
                null,
                null,
                null,
                SpecialAction.RALLY,
                EVENT,
                UNBANDED,
                null,
                List.of(),
                List.of());

        assertThat(commitment(Faction.PROPHETS, foresight)).isEqualTo(HIGH_BAND);
        assertThat(commitment(Faction.REVISIONISTS, rewrite)).isEqualTo(HIGH_BAND);
        assertThat(commitment(Faction.WEAVERS, thread, reweave)).isEqualTo(HIGH_BAND);
        assertThat(commitment(Faction.ACTIVISTS, rally)).isEqualTo(UNBANDED);
        assertThat(commitment(Faction.ERASERS, foresight)).isNull();
    }

    private static UUID commitment(Faction faction, Board.OwnSubmission... own) {
        var reading = BoardReading.of(observation(
                faction,
                List.of(
                        new OutcomeView(LOW_PRINTED_HIGH, 20, null),
                        new OutcomeView(HIGH_BAND, 30, null),
                        new OutcomeView(UNBANDED, 50, null)),
                List.of(own)));
        return List.of(LOW_PRINTED_HIGH, HIGH_BAND, UNBANDED).stream()
                .filter(reading::isCommitment)
                .findFirst()
                .orElse(null);
    }

    private static Board.OwnSubmission own(int round, SpecialAction action, UUID outcomeId) {
        return new Board.OwnSubmission(
                WindowKind.ACTION_ROUND, round, null, null, action, EVENT, outcomeId, null, List.of(), List.of());
    }

    private static EntitledObservation observation(
            Faction faction, List<OutcomeView> outcomes, List<Board.OwnSubmission> own) {
        return new EntitledObservation(
                0,
                faction,
                List.of(new EventView(EVENT, outcomes)),
                PolicyFixtures.players(),
                new DecisionWindow.ActionRound(1, 2, List.of(), List.of(), List.of()),
                new Board(List.of(), null, own, null, List.of()));
    }
}
