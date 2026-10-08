package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActiveEvent;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardGrade;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.DealtHandCard;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.EventOutcome;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Faction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.HandCard;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.MySubmission;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PendingHandSelection;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Phase;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PhaseContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerInGame;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionProgress;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionWindow;

/** Builds participant projection responses for the seat whose player id is {@link #ME}. */
final class ProjectionStates {

    static final UUID GAME = id(0xA1);
    static final UUID ME = id(1);
    static final UUID OTHER_A = id(2);
    static final UUID OTHER_B = id(3);
    static final UUID EVENT = id(0xE0);
    static final UUID OUTCOME_LOW = id(0xA1A);
    static final UUID OUTCOME_HIGH = id(0xA1B);

    private ProjectionStates() {}

    static UUID id(long n) {
        return new UUID(0L, n);
    }

    /** A state in the given phase with one active event and three players, the caller an Eraser. */
    static PlayerGameStateResponse base(Phase phase, int era) {
        var event = new ActiveEvent(
                EVENT,
                "Storm",
                ActiveEvent.CarryOverStateEnum.FRESH,
                List.of(new EventOutcome(OUTCOME_LOW, "calm", 40), new EventOutcome(OUTCOME_HIGH, "storm", 60)));
        var state = new PlayerGameStateResponse(
                GAME,
                era,
                20,
                phase,
                new ArrayList<>(),
                0,
                new ArrayList<>(),
                new ArrayList<>(List.of(event)),
                new ArrayList<>(List.of(
                        new PlayerInGame(ME, 0, true),
                        new PlayerInGame(OTHER_A, 0, true),
                        new PlayerInGame(OTHER_B, 0, true))));
        state.setMyFaction(Faction.ERASERS);
        state.setRevision(1);
        return state;
    }

    static PlayerGameStateResponse handSelection(int era) {
        var state = base(Phase.HAND_SELECTION, era);
        var deal = new ArrayList<DealtHandCard>();
        var types = List.of(
                CardType.PUSH,
                CardType.SUPPRESS,
                CardType.SWING,
                CardType.SCAN,
                CardType.JAM,
                CardType.DECOY,
                CardType.NULLIFY);
        for (var slot = 0; slot < 7; slot++) {
            deal.add(new DealtHandCard(id(100 + slot), types.get(slot), CardGrade.II, slot + 1));
        }
        state.setPendingHandSelection(new PendingHandSelection(
                deal, PendingHandSelection.RequiredSelectionCountEnum.NUMBER_5, OffsetDateTime.now()));
        return state;
    }

    /** An action round in which the caller still has to decide and holds the given playable cards. */
    static PlayerGameStateResponse actionRound(int era, int round, List<HandCard> hand) {
        var state = base(Phase.valueOf("ACTION_ROUND_" + round), era);
        state.setRoundNumber(round);
        state.setMyHand(new ArrayList<>(hand));
        var context = new PhaseContext(false, false);
        context.setActionRoundProgress(new SubmissionProgress(0, 3, Set.of(ME, OTHER_A, OTHER_B)));
        state.setPhaseContext(context);
        return state;
    }

    static HandCard card(long n, CardType type, CardGrade grade, boolean playable) {
        return new HandCard(id(n), type, grade, playable);
    }

    static MySubmission accepted(SubmissionWindow window, int era, Integer round) {
        var submission = new MySubmission(era, window, MySubmission.StatusEnum.ACCEPTED);
        submission.setRoundNumber(round);
        return submission;
    }
}
