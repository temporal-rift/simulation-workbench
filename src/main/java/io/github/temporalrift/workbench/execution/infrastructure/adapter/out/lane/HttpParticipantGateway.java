package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.springframework.web.client.RestClientException;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.WindowClosedException;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.ActionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.ActionType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.ActivistDeclarationMode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.ActivistDeclarationRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.CardActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.CardCategory;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.HandSelectionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.ParadoxResolutionCardRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.PassActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.SpecialAction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.SpecialActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.SubmitActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/**
 * The participant operations of one game over the generated clients. Every call is made as the seat's own
 * authenticated bot, and every observation is built from that bot's own projection response. Execution
 * control is never reachable from here.
 */
public class HttpParticipantGateway implements ParticipantGateway, SlotReconciler {

    private final UUID gameId;
    private final Map<Integer, Participant> participants;
    private final BooleanSupplier settled;
    private final Map<Integer, ObservationMapper.Owed> open = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> ready = new ConcurrentHashMap<>();

    /** One seat's bot identity and its clients. */
    public record Participant(int seatIndex, UUID playerId, ProjectionApi projection, ActionApi action) {}

    /**
     * @param settled whether the services and projection have settled, so accepted state is current
     */
    public HttpParticipantGateway(UUID gameId, List<Participant> seats, BooleanSupplier settled) {
        this.gameId = gameId;
        this.participants = new ConcurrentHashMap<>();
        seats.forEach(seat -> participants.put(seat.seatIndex(), seat));
        this.settled = settled;
    }

    UUID gameId() {
        return gameId;
    }

    List<Participant> seats() {
        return participants.values().stream()
                .sorted(Comparator.comparingInt(Participant::seatIndex))
                .toList();
    }

    @Override
    public EntitledObservation observe(int seatIndex) {
        var participant = participant(seatIndex);
        var state = state(participant);
        if (state.getPhase().name().equals("GAME_ENDED")) {
            open.remove(seatIndex);
            return ObservationMapper.terminal(seatIndex, participant.playerId(), state);
        }
        var owed = ObservationMapper.owed(seatIndex, participant.playerId(), state)
                .orElseThrow(() -> new WindowClosedException(seatIndex));
        open.put(seatIndex, owed);
        return owed.observation();
    }

    @Override
    public SubmissionOutcome submit(int seatIndex, Candidate candidate) {
        if (candidate instanceof Candidate.ConfirmReady) {
            ready.put(seatIndex, true);
            return new SubmissionOutcome.Accepted();
        }
        var owed = open.get(seatIndex);
        if (owed == null) {
            throw new WindowClosedException(seatIndex);
        }
        var action = participant(seatIndex).action();
        try {
            send(action, owed, candidate);
            return new SubmissionOutcome.Accepted();
        } catch (RestClientException e) {
            return ProblemCodes.outcomeOf(e);
        }
    }

    @Override
    public Reconciliation reconcile(int seatIndex) {
        if (ready.containsKey(seatIndex)) {
            return new Reconciliation.Accepted(new Candidate.ConfirmReady());
        }
        var owed = open.get(seatIndex);
        if (owed == null) {
            throw new WindowClosedException(seatIndex);
        }
        if (!settled.getAsBoolean()) {
            return new Reconciliation.Pending();
        }
        var participant = participant(seatIndex);
        var state = state(participant);
        return AcceptedState.accepted(owed.observation().window(), owed.round(), participant.playerId(), state)
                .<Reconciliation>map(Reconciliation.Accepted::new)
                .orElseGet(Reconciliation.NotAccepted::new);
    }

    @Override
    public Optional<Boolean> holds(int seatIndex, String windowKey) {
        if (!settled.getAsBoolean()) {
            return Optional.empty();
        }
        return Optional.of(AcceptedState.holds(stateOf(seatIndex), WindowKeys.parse(windowKey)));
    }

    private void send(ActionApi action, ObservationMapper.Owed owed, Candidate candidate) {
        var era = owed.era();
        switch (candidate) {
            case Candidate.KeepHand(var cardInstanceIds) ->
                action.selectHand(gameId, era, new HandSelectionRequest(new LinkedHashSet<>(cardInstanceIds)));
            case Candidate.Declare(var mode, var eventId, var outcomeId) ->
                action.recordActivistDeclaration(
                        gameId,
                        era,
                        new ActivistDeclarationRequest(
                                ActivistDeclarationMode.valueOf(mode.name()), eventId, outcomeId));
            case Candidate.Decline _ -> action.declineDeclaration(gameId, era);
            case Candidate.PlayCard(var cardInstanceId, var target) ->
                action.submitAction(gameId, era, owed.round(), cardRequest(cardInstanceId, target));
            case Candidate.PlaySpecial(var special, var target) ->
                action.submitAction(gameId, era, owed.round(), specialRequest(special, target));
            case Candidate.Pass _ ->
                action.submitAction(gameId, era, owed.round(), new PassActionRequest(ActionType.PASS));
            case Candidate.PlayParadoxCard(var cardInstanceId, var target) ->
                action.submitParadoxResolutionCard(
                        gameId,
                        era,
                        new ParadoxResolutionCardRequest()
                                .actionType(ActionType.CARD)
                                .cardInstanceId(cardInstanceId)
                                .targetEventId(target.eventId())
                                .targetOutcomeId(target.outcomeId()));
            case Candidate.PassParadox _ ->
                action.submitParadoxResolutionCard(
                        gameId, era, new ParadoxResolutionCardRequest().actionType(ActionType.PASS));
            case Candidate.ConfirmReady _ -> throw new IllegalStateException("Terminal readiness sends nothing");
        }
    }

    private static SubmitActionRequest cardRequest(UUID cardInstanceId, Target target) {
        var request = new CardActionRequest(cardInstanceId, ActionType.CARD);
        switch (target) {
            case Target.Disguise(var category) -> request.disguiseCategory(CardCategory.valueOf(category.name()));
            case Target.EventOutcome(var eventId, var outcomeId) ->
                request.targetEventId(eventId).targetOutcomeId(outcomeId);
            case Target.OutcomePair(var eventId, var sourceOutcomeId, var targetOutcomeId) ->
                request.targetEventId(eventId).sourceOutcomeId(sourceOutcomeId).targetOutcomeId(targetOutcomeId);
            case Target.Events(var eventIds) -> request.targetEventIds(eventIds);
            case Target.Player(var playerId) -> request.targetPlayerId(playerId);
            case Target.Players(var playerIds) -> request.targetPlayerIds(playerIds);
        }
        return request;
    }

    private static SubmitActionRequest specialRequest(Enum<?> special, Target target) {
        var request = new SpecialActionRequest(SpecialAction.valueOf(special.name()), ActionType.SPECIAL);
        switch (target) {
            case Target.EventOutcome(var eventId, var outcomeId) ->
                request.targetEventId(eventId).targetOutcomeId(outcomeId);
            case Target.Player(var playerId) -> request.targetPlayerId(playerId);
            default ->
                throw new AttemptFailedException(
                        FailureCode.CONTRACT_MISMATCH,
                        "A special action cannot take the target "
                                + target.getClass().getSimpleName());
        }
        return request;
    }

    /** The seat's own current game state, as its authenticated bot reads it. */
    PlayerGameStateResponse stateOf(int seatIndex) {
        return state(participant(seatIndex));
    }

    private PlayerGameStateResponse state(Participant participant) {
        try {
            var response = participant.projection().getGameState(gameId);
            if (response.getBody() == null) {
                throw new AttemptFailedException(
                        FailureCode.CONTRACT_MISMATCH, "The projection returned no game state");
            }
            return response.getBody();
        } catch (RestClientException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "The game state of seat " + participant.seatIndex() + " could not be read: " + e.getMessage(),
                    e);
        }
    }

    private Participant participant(int seatIndex) {
        var participant = participants.get(seatIndex);
        if (participant == null) {
            throw new AttemptFailedException(FailureCode.CONTRACT_MISMATCH, "Unknown seat " + seatIndex);
        }
        return participant;
    }
}
