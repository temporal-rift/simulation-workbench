package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActionFamily;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActionSummary;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActiveEvent;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardCategory;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardGrade;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ChainState;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerInGame;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PublicBandEvent;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PublicBandOutcome;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PublicDeclaration;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.RoundSummary;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SpecialAction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionTargets;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionWindow;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmittedCard;
import io.github.temporalrift.workbench.policy.domain.observation.Band;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PublicBand;
import io.github.temporalrift.workbench.shared.window.WindowKind;

class BoardMapperTest {

    private static final int SEAT = 1;

    @Test
    void anActionRoundCarriesTheSettledBoardOfTheEra() {
        var state = ProjectionStates.actionRound(2, 3, List.of());
        state.setPublicBands(new ArrayList<>(List.of(new PublicBandEvent(
                ProjectionStates.EVENT,
                2,
                List.of(
                        new PublicBandOutcome(ProjectionStates.OUTCOME_LOW, PublicBandOutcome.BandEnum.LOW),
                        new PublicBandOutcome(ProjectionStates.OUTCOME_HIGH, PublicBandOutcome.BandEnum.HIGH))))));
        state.setDeclarations(new ArrayList<>(List.of(new PublicDeclaration(
                ProjectionStates.OTHER_A,
                PublicDeclaration.ModeEnum.RALLY,
                ProjectionStates.EVENT,
                ProjectionStates.OUTCOME_HIGH,
                2))));
        var shifter = new ActionSummary(ProjectionStates.OTHER_A, false);
        shifter.setActionCategory(CardCategory.PROBABILITY_SHIFTER);
        shifter.setActionFamily(ActionFamily.CARD);
        state.setLastRoundSummary(new RoundSummary(2, List.of(shifter)));
        var thread = ProjectionStates.accepted(SubmissionWindow.ACTION, 2, 1);
        thread.setSpecialAction(SpecialAction.THREAD);
        var targets = new SubmissionTargets();
        targets.setTargetEventId(ProjectionStates.EVENT);
        targets.setTargetOutcomeId(ProjectionStates.OUTCOME_HIGH);
        thread.setTargets(targets);
        var push = ProjectionStates.accepted(SubmissionWindow.ACTION, 2, 2);
        push.setCard(new SubmittedCard(ProjectionStates.id(0x77), CardType.PUSH, CardGrade.II));
        var earlierEra = ProjectionStates.accepted(SubmissionWindow.ACTION, 1, 1);
        state.getMySubmissions().addAll(List.of(thread, push, earlierEra));
        state.setChain(new ChainState(ChainState.StatusEnum.ACTIVE, 2));

        var observation = ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation();

        assertThat(observation.events().getFirst().outcomes())
                .extracting(OutcomeView::band)
                .containsExactly(new PublicBand(Band.LOW, 2), new PublicBand(Band.HIGH, 2));
        var board = observation.board();
        assertThat(board.declarations())
                .containsExactly(new Board.PublicDeclaration(
                        ProjectionStates.OTHER_A,
                        DeclarationMode.RALLY,
                        ProjectionStates.EVENT,
                        ProjectionStates.OUTCOME_HIGH));
        assertThat(board.lastRoundSummary().round()).isEqualTo(2);
        assertThat(board.lastRoundSummary().actions())
                .singleElement()
                .satisfies(action -> assertThat(action.showsShifterCard()).isTrue());
        assertThat(board.ownSubmissions()).hasSize(2);
        assertThat(board.ownSubmissions().getFirst()).satisfies(own -> {
            assertThat(own.window()).isEqualTo(WindowKind.ACTION_ROUND);
            assertThat(own.round()).isEqualTo(1);
            assertThat(own.special()).isEqualTo(io.github.temporalrift.workbench.shared.game.SpecialAction.THREAD);
            assertThat(own.outcomeId()).isEqualTo(ProjectionStates.OUTCOME_HIGH);
        });
        assertThat(board.ownSubmissions().get(1).card())
                .isEqualTo(io.github.temporalrift.workbench.shared.game.CardType.PUSH);
        assertThat(board.chain()).isEqualTo(new Board.ChainView(Board.ChainStatus.ACTIVE, 2));
        assertThat(board.openParadoxes()).isEmpty();
    }

    @Test
    void aRoundOneWindowStillSeesThePreviousErasLastSummary() {
        var state = ProjectionStates.actionRound(3, 1, List.of());
        state.setLastRoundSummary(new RoundSummary(3, List.of(new ActionSummary(ProjectionStates.OTHER_B, true))));

        var board = ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .board();

        assertThat(board.lastRoundSummary().round()).isEqualTo(3);
        assertThat(board.lastRoundSummary().actions().getFirst().skipped()).isTrue();
        assertThat(board.declarations()).isEmpty();
        assertThat(board.chain()).isNull();
    }

    @Test
    void thePublicScoresReachTheBoardAsTheSeatsOwnAndTheOthers() {
        var state = ProjectionStates.actionRound(2, 2, List.of());
        state.setMyScore(17);
        state.getPlayers().forEach(player -> player.setScore(9));

        var scores = ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .board()
                .scores();

        assertThat(scores.own()).isEqualTo(17);
        assertThat(scores.others()).isNotEmpty().containsOnly(9);
        assertThat(scores.leads()).isTrue();
        assertThat(state.getPlayers()).extracting(PlayerInGame::getScore).containsOnly(9);
    }

    @Test
    void aStalledEventIsMarkedStalledInTheObservation() {
        var state = ProjectionStates.actionRound(2, 2, List.of());
        state.getActiveEvents().getFirst().setCarryOverState(ActiveEvent.CarryOverStateEnum.STALLED);

        var events = ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .events();

        assertThat(events.getFirst().stalled()).isTrue();
        assertThat(events.stream().skip(1)).noneMatch(event -> event.stalled());
    }
}
