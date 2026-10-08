package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import io.github.temporalrift.workbench.execution.domain.run.WindowClosedException;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.ActionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.ActionType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.CardActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.HandSelectionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.ParadoxResolutionCardRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.SpecialActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.SubmitActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActivistDeclarationMode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardGrade;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.EligibleResolutionCard;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Phase;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PhaseContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionChoice;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionTargets;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionWindow;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmittedCard;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

class HttpParticipantGatewayTest {

    private static final UUID EVENT = ProjectionStates.EVENT;
    private static final UUID LOW = ProjectionStates.OUTCOME_LOW;
    private static final UUID HIGH = ProjectionStates.OUTCOME_HIGH;

    private final ProjectionApi projection = mock(ProjectionApi.class);
    private final ActionApi action = mock(ActionApi.class);
    private final AtomicBoolean settled = new AtomicBoolean(true);
    private HttpParticipantGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new HttpParticipantGateway(
                ProjectionStates.GAME,
                List.of(new HttpParticipantGateway.Participant(1, ProjectionStates.ME, projection, action)),
                settled::get);
    }

    @Test
    void observesTheSeatsOwnWindowThroughItsOwnProjectionClient() {
        state(ProjectionStates.handSelection(1));

        var observation = gateway.observe(1);

        assertThat(observation.window()).isInstanceOf(DecisionWindow.HandSelection.class);
        verify(projection).getGameState(ProjectionStates.GAME);
    }

    @Test
    void aWindowThatClosedBeforeObservationIsReportedAsClosed() {
        state(ProjectionStates.base(Phase.RESOLUTION, 1));

        assertThatThrownBy(() -> gateway.observe(1)).isInstanceOf(WindowClosedException.class);
    }

    @Test
    void anEndedGameIsObservedAsTerminalReadinessAndConfirmedWithoutAnyCall() {
        state(ProjectionStates.base(Phase.GAME_ENDED, 5));

        var observation = gateway.observe(1);

        assertThat(observation.window()).isEqualTo(new DecisionWindow.TerminalReadiness(5));
        assertThat(gateway.submit(1, new Candidate.ConfirmReady())).isInstanceOf(SubmissionOutcome.Accepted.class);
        assertThat(gateway.reconcile(1)).isEqualTo(new Reconciliation.Accepted(new Candidate.ConfirmReady()));
        verifyNoInteractions(action);
    }

    @Test
    void submittingWithoutAnObservedWindowIsRefused() {
        assertThatThrownBy(() -> gateway.submit(1, new Candidate.Pass())).isInstanceOf(WindowClosedException.class);
    }

    @Test
    void handSelectionPostsExactlyTheKeptCardsToTheObservedEra() {
        state(ProjectionStates.handSelection(2));
        gateway.observe(1);
        var kept = List.of(new UUID(0, 100), new UUID(0, 101), new UUID(0, 102), new UUID(0, 103), new UUID(0, 104));

        var outcome = gateway.submit(1, new Candidate.KeepHand(kept));

        assertThat(outcome).isInstanceOf(SubmissionOutcome.Accepted.class);
        var request = ArgumentCaptor.forClass(HandSelectionRequest.class);
        verify(action).selectHand(eq(ProjectionStates.GAME), eq(2), request.capture());
        assertThat(request.getValue().getKeptCardInstanceIds()).containsExactlyInAnyOrderElementsOf(kept);
    }

    @Test
    void cardTargetsMapToTheFieldsEachShapeRequires() {
        state(ProjectionStates.actionRound(1, 2, List.of()));
        gateway.observe(1);
        var card = new UUID(0, 7);
        var other = new UUID(0, 8);

        gateway.submit(1, new Candidate.PlayCard(card, new Target.EventOutcome(EVENT, HIGH)));
        gateway.submit(1, new Candidate.PlayCard(card, new Target.OutcomePair(EVENT, LOW, HIGH)));
        gateway.submit(1, new Candidate.PlayCard(card, new Target.Events(List.of(EVENT))));
        gateway.submit(1, new Candidate.PlayCard(card, new Target.Player(other)));
        gateway.submit(1, new Candidate.PlayCard(card, new Target.Players(List.of(other, ProjectionStates.OTHER_B))));
        gateway.submit(1, new Candidate.PlayCard(card, new Target.Disguise(CardCategory.INFORMATION)));

        var requests = captureActions(6);
        var eventOutcome = (CardActionRequest) requests.get(0);
        assertThat(eventOutcome.getActionType()).isEqualTo(ActionType.CARD);
        assertThat(eventOutcome.getCardInstanceId()).isEqualTo(card);
        assertThat(eventOutcome.getTargetEventId()).isEqualTo(EVENT);
        assertThat(eventOutcome.getTargetOutcomeId()).isEqualTo(HIGH);
        assertThat(eventOutcome.getSourceOutcomeId()).isNull();
        var pair = (CardActionRequest) requests.get(1);
        assertThat(pair.getTargetEventId()).isEqualTo(EVENT);
        assertThat(pair.getSourceOutcomeId()).isEqualTo(LOW);
        assertThat(pair.getTargetOutcomeId()).isEqualTo(HIGH);
        assertThat(((CardActionRequest) requests.get(2)).getTargetEventIds()).containsExactly(EVENT);
        assertThat(((CardActionRequest) requests.get(2)).getTargetEventId()).isNull();
        assertThat(((CardActionRequest) requests.get(3)).getTargetPlayerId()).isEqualTo(other);
        assertThat(((CardActionRequest) requests.get(4)).getTargetPlayerIds())
                .containsExactly(other, ProjectionStates.OTHER_B);
        assertThat(((CardActionRequest) requests.get(5)).getDisguiseCategory().name())
                .isEqualTo("INFORMATION");
    }

    @Test
    void specialsAndPassesAreAddressedToTheObservedRound() {
        state(ProjectionStates.actionRound(3, 2, List.of()));
        gateway.observe(1);

        gateway.submit(1, new Candidate.PlaySpecial(SpecialAction.ANNIHILATE, new Target.EventOutcome(EVENT, LOW)));
        gateway.submit(1, new Candidate.PlaySpecial(SpecialAction.EXPOSE, new Target.Player(ProjectionStates.OTHER_A)));
        gateway.submit(1, new Candidate.Pass());

        verify(action, org.mockito.Mockito.times(3)).submitAction(eq(ProjectionStates.GAME), eq(3), eq(2), any());
        var requests = captureActions(3);
        var annihilate = (SpecialActionRequest) requests.get(0);
        assertThat(annihilate.getSpecialAction())
                .isEqualTo(
                        io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model
                                .SpecialAction.ANNIHILATE);
        assertThat(annihilate.getTargetEventId()).isEqualTo(EVENT);
        assertThat(annihilate.getTargetOutcomeId()).isEqualTo(LOW);
        assertThat(((SpecialActionRequest) requests.get(1)).getTargetPlayerId()).isEqualTo(ProjectionStates.OTHER_A);
        assertThat(requests.get(2).getActionType()).isEqualTo(ActionType.PASS);
    }

    @Test
    void declarationsAndParadoxResolutionUseTheirOwnOperations() {
        var declaration = ProjectionStates.base(Phase.ERA_START, 2);
        declaration.setPhaseContext(new PhaseContext(true, false));
        declaration.setMyEligibleDeclarationModes(Set.of(ActivistDeclarationMode.RALLY));
        state(declaration);
        gateway.observe(1);

        gateway.submit(1, new Candidate.Declare(DeclarationMode.RALLY, EVENT, HIGH));
        gateway.submit(1, new Candidate.Decline());

        verify(action).recordActivistDeclaration(eq(ProjectionStates.GAME), eq(2), any());
        verify(action).declineDeclaration(ProjectionStates.GAME, 2);

        var paradox = ProjectionStates.base(Phase.PARADOX_RESOLUTION, 4);
        paradox.setPhaseContext(new PhaseContext(false, true));
        paradox.setMyEligibleResolutionCards(
                List.of(new EligibleResolutionCard(new UUID(0, 9), CardType.STABILIZE, CardGrade.II)));
        state(paradox);
        gateway.observe(1);

        gateway.submit(1, new Candidate.PlayParadoxCard(new UUID(0, 9), new Target.EventOutcome(EVENT, HIGH)));
        gateway.submit(1, new Candidate.PassParadox());

        var request = ArgumentCaptor.forClass(ParadoxResolutionCardRequest.class);
        verify(action, org.mockito.Mockito.times(2))
                .submitParadoxResolutionCard(eq(ProjectionStates.GAME), eq(4), request.capture());
        assertThat(request.getAllValues().get(0).getActionType()).isEqualTo(ActionType.CARD);
        assertThat(request.getAllValues().get(0).getCardInstanceId()).isEqualTo(new UUID(0, 9));
        assertThat(request.getAllValues().get(0).getTargetEventId()).isEqualTo(EVENT);
        assertThat(request.getAllValues().get(1).getActionType()).isEqualTo(ActionType.PASS);
        assertThat(request.getAllValues().get(1).getCardInstanceId()).isNull();
    }

    @Test
    void aDefiniteRejectionCarriesTheServicesProblemCode() {
        state(ProjectionStates.actionRound(1, 1, List.of()));
        gateway.observe(1);
        when(action.submitAction(any(), any(), any(), any()))
                .thenThrow(
                        problem(HttpStatus.UNPROCESSABLE_CONTENT, "{\"code\":\"422-03\",\"detail\":\"bad target\"}"));

        var outcome = gateway.submit(1, new Candidate.Pass());

        assertThat(outcome).isEqualTo(new SubmissionOutcome.Rejected("422-03"));
    }

    @Test
    void aRejectionWithoutABodyFallsBackToTheHttpStatus() {
        state(ProjectionStates.actionRound(1, 1, List.of()));
        gateway.observe(1);
        when(action.submitAction(any(), any(), any(), any())).thenThrow(problem(HttpStatus.CONFLICT, ""));

        assertThat(gateway.submit(1, new Candidate.Pass())).isEqualTo(new SubmissionOutcome.Rejected("409"));
    }

    @Test
    void aLostResponseAndAServerErrorLeaveTheOutcomeUnknown() {
        state(ProjectionStates.actionRound(1, 1, List.of()));
        gateway.observe(1);
        when(action.submitAction(any(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("connection reset"))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY, "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8));

        assertThat(gateway.submit(1, new Candidate.Pass())).isInstanceOf(SubmissionOutcome.Unacknowledged.class);
        assertThat(gateway.submit(1, new Candidate.Pass())).isInstanceOf(SubmissionOutcome.Unacknowledged.class);
    }

    @Test
    void reconciliationIsPendingUntilTheGameHasSettled() {
        state(ProjectionStates.actionRound(1, 1, List.of()));
        gateway.observe(1);
        settled.set(false);

        assertThat(gateway.reconcile(1)).isInstanceOf(Reconciliation.Pending.class);
        assertThat(gateway.holds(1, "era1/round1/action")).isEmpty();
    }

    @Test
    void reconciliationFindsAnAcceptedPassInTheSeatsOwnState() {
        state(ProjectionStates.actionRound(1, 1, List.of()));
        gateway.observe(1);
        var after = ProjectionStates.actionRound(1, 1, List.of());
        var accepted = ProjectionStates.accepted(SubmissionWindow.ACTION, 1, 1);
        accepted.setChoice(SubmissionChoice.PASS);
        after.getMySubmissions().add(accepted);
        state(after);

        assertThat(gateway.reconcile(1)).isEqualTo(new Reconciliation.Accepted(new Candidate.Pass()));
        assertThat(gateway.holds(1, "era1/round1/action")).contains(true);
    }

    @Test
    void reconciliationFindsAnAcceptedCardWithItsTargets() {
        state(ProjectionStates.actionRound(1, 1, List.of()));
        gateway.observe(1);
        var after = ProjectionStates.actionRound(1, 1, List.of());
        var accepted = ProjectionStates.accepted(SubmissionWindow.ACTION, 1, 1);
        accepted.setChoice(SubmissionChoice.CARD);
        accepted.setCard(new SubmittedCard(new UUID(0, 7), CardType.SWING, CardGrade.I));
        var targets = new SubmissionTargets();
        targets.setTargetEventId(EVENT);
        targets.setSourceOutcomeId(LOW);
        targets.setTargetOutcomeId(HIGH);
        accepted.setTargets(targets);
        after.getMySubmissions().add(accepted);
        state(after);

        assertThat(gateway.reconcile(1))
                .isEqualTo(new Reconciliation.Accepted(
                        new Candidate.PlayCard(new UUID(0, 7), new Target.OutcomePair(EVENT, LOW, HIGH))));
    }

    @Test
    void reconciliationConcludesNotAcceptedOnlyFromCurrentState() {
        state(ProjectionStates.actionRound(1, 1, List.of()));
        gateway.observe(1);

        assertThat(gateway.reconcile(1)).isInstanceOf(Reconciliation.NotAccepted.class);
        assertThat(gateway.holds(1, "era1/round1/action")).contains(false);
    }

    @Test
    void anUnreadableStateFailsTheAttemptInsteadOfGuessing() {
        when(projection.getGameState(any())).thenThrow(new ResourceAccessException("down"));

        assertThatThrownBy(() -> gateway.observe(1)).hasMessageContaining("could not be read");
        verify(action, never()).submitAction(any(), any(), any(), any());
    }

    private void state(PlayerGameStateResponse state) {
        when(projection.getGameState(ProjectionStates.GAME)).thenReturn(ResponseEntity.ok(state));
    }

    private List<SubmitActionRequest> captureActions(int times) {
        var captor = ArgumentCaptor.forClass(SubmitActionRequest.class);
        verify(action, org.mockito.Mockito.times(times)).submitAction(any(), any(), any(), captor.capture());
        return captor.getAllValues();
    }

    private static HttpClientErrorException problem(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
