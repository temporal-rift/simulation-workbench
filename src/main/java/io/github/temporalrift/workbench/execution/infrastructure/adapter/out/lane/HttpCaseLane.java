package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.ActionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.EffectiveRules;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.EffectiveRulesPropertiesValue;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionCheckpoint;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.Faction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.SimulationSeat;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.scoring.ScoringApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.SessionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.CreateLobbyRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.JoinLobbyRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.LobbyStatus;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

/**
 * One isolated lane hosting one case at a time. Opening configures the case's execution context, with the rules it
 * plays under, on both services, then plays the lobby and start through the bots' own session operations. Closing the
 * lane closes the context on every service it was sent to.
 */
public class HttpCaseLane implements CaseLane {

    private static final Duration START_RECONCILE_BUDGET = Duration.ofMillis(2500);
    /** Allowance for the difference between this clock and the producers' record timestamps. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final LaneEndpoints lane;
    private final ApiClients clients;
    private final CommandLedger ledger;
    private final EvidenceLedger evidence;
    private final GameEventObservers observers;
    private final List<GameEventObserver> opened = new CopyOnWriteArrayList<>();
    private final Clock clock;
    private final HttpGameSession.Pacing pacing;
    private final LaneRoster.Tenancy tenancy;

    public HttpCaseLane(
            LaneEndpoints lane,
            ApiClients clients,
            LaneServices services,
            Clock clock,
            HttpGameSession.Pacing pacing,
            LaneRoster.Tenancy tenancy) {
        this.lane = lane;
        this.clients = clients;
        this.ledger = services.commands();
        this.evidence = services.evidence();
        this.observers = services.observers();
        this.clock = clock;
        this.pacing = pacing;
        this.tenancy = tenancy;
    }

    @Override
    public String laneId() {
        return lane.id();
    }

    @Override
    public GameSession open(CaseContext context) {
        if (lane.bots().size() < context.seats().size()) {
            throw new AttemptFailedException(
                    FailureCode.CONTRACT_MISMATCH,
                    "Lane " + lane.id() + " has " + lane.bots().size() + " bot identities for "
                            + context.seats().size() + " seats");
        }
        tenancy.host(context);
        var gameControl = clients.create(SimulationExecutionApi.class, lane.gameServiceUrl(), lane.operatorToken());
        var timelineControl =
                clients.create(SimulationExecutionApi.class, lane.timelineServiceUrl(), lane.operatorToken());
        var participants = participants(context);
        // The slots of an abandoned game must neither block nor appear in this transcript.
        ledger.reset(context.caseId());
        var since = clock.instant().minus(CLOCK_SKEW);
        var gameId = startGame(context, gameControl, timelineControl);
        return session(context, gameId, gameControl, timelineControl, participants, since);
    }

    @Override
    public void close() {
        try {
            opened.forEach(GameEventObserver::close);
            opened.clear();
        } finally {
            tenancy.giveBack();
        }
    }

    private HttpGameSession session(
            CaseContext context,
            UUID gameId,
            SimulationExecutionApi gameControl,
            SimulationExecutionApi timelineControl,
            List<HttpParticipantGateway.Participant> participants,
            Instant since) {
        var scoring = clients.create(
                ScoringApi.class, lane.gameServiceUrl(), lane.bots().getFirst().token());
        var sessionRef = new AtomicReference<HttpGameSession>();
        var raw = new HttpParticipantGateway(
                gameId, participants, () -> sessionRef.get().isSettled(), pacing.waits());
        var recorder = new StepRecorder(
                evidence,
                context.caseId(),
                gameId,
                context.attemptId(),
                context.seed(),
                () -> sessionRef.get().logicalTime());
        var durable = new LedgerParticipantGateway(raw, ledger, recorder, context.caseId(), context.attemptId(), clock);
        var events = observers.open(lane, context.caseId(), context.attemptId(), gameId, since);
        opened.add(events);
        var session = new HttpGameSession(
                context,
                new HttpGameSession.ServiceControls(gameControl, timelineControl),
                raw,
                durable,
                scoring,
                pacing,
                events);
        sessionRef.set(session);
        return session;
    }

    private List<HttpParticipantGateway.Participant> participants(CaseContext context) {
        var participants = new ArrayList<HttpParticipantGateway.Participant>();
        for (var seat : context.seats()) {
            var bot = lane.bots().get(seat.seatIndex());
            participants.add(new HttpParticipantGateway.Participant(
                    seat.seatIndex(),
                    bot.playerId(),
                    clients.create(ProjectionApi.class, lane.readServiceUrl(), bot.token()),
                    clients.create(ActionApi.class, lane.gameServiceUrl(), bot.token())));
        }
        return participants;
    }

    private UUID startGame(
            CaseContext context, SimulationExecutionApi gameControl, SimulationExecutionApi timelineControl) {
        var game = configure(gameControl, LaneService.GAME, context);
        var timeline = configure(timelineControl, LaneService.TIMELINE, context);
        evidence.reportRulesDigests(context.caseId(), game.getRulesDigest(), timeline.getRulesDigest());
        var host = lane.bots().getFirst();
        var hostSession = clients.create(SessionApi.class, lane.gameServiceUrl(), host.token());
        var lobbyId = createLobby(hostSession, host);
        for (var seat : context.seats().subList(1, context.seats().size())) {
            var bot = lane.bots().get(seat.seatIndex());
            join(lobbyId, bot, seat.seatIndex(), hostSession);
        }
        return start(hostSession, lobbyId);
    }

    /** Opens the context on one service; the service reports the rules it adopted, which must be the case's. */
    private ExecutionCheckpoint configure(SimulationExecutionApi control, LaneService service, CaseContext context) {
        var seats = context.seats().stream()
                .map(seat -> new SimulationSeat(
                        seat.seatIndex(),
                        lane.bots().get(seat.seatIndex()).playerId(),
                        Faction.valueOf(seat.faction().name())))
                .toList();
        var request = new ExecutionContext()
                .schemaVersion(ExecutionContext.SchemaVersionEnum.NUMBER_1)
                .caseKey(context.caseKey())
                .seed(context.seed())
                .entropyVersion(ExecutionContext.EntropyVersionEnum.SHA256_V1)
                .manifestDigest(context.manifestDigest())
                .rules(rules(context))
                .rulesDigest(context.rules().digest())
                .logicalTime(context.logicalEpoch().atOffset(java.time.ZoneOffset.UTC))
                .seats(seats);
        // From the first byte sent the service may hold the context, so it has to be closed even if no answer arrives.
        tenancy.opened(service);
        try {
            ExecutionCheckpoint checkpoint =
                    control.configureSimulationExecution(request).getBody();
            if (checkpoint == null
                    || !context.caseKey().equals(checkpoint.getCaseKey())
                    || !context.manifestDigest().equals(checkpoint.getManifestDigest())
                    || !context.rules().digest().equals(checkpoint.getRulesDigest())) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT,
                        service.serviceName() + " did not adopt the case's execution context");
            }
            return checkpoint;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                tenancy.refused(service);
            }
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    service.serviceName() + " refused the execution context: " + ProblemCodes.codeOf(e) + " "
                            + ProblemCodes.describe(e),
                    e);
        } catch (RestClientException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    service.serviceName() + " could not be configured for the case: " + e.getMessage(),
                    e);
        }
    }

    private static EffectiveRules rules(CaseContext context) {
        var properties = new LinkedHashMap<String, EffectiveRulesPropertiesValue>();
        context.rules().properties().forEach((key, value) -> properties.put(key, new RulesValue(value)));
        return new EffectiveRules(EffectiveRules.SchemaVersionEnum.NUMBER_1, properties);
    }

    /** A rules value as the JSON number, boolean, string or array it is; the generated union names none of them. */
    private record RulesValue(@JsonValue Object value) implements EffectiveRulesPropertiesValue {}

    private UUID createLobby(SessionApi hostSession, LaneEndpoints.BotIdentity host) {
        try {
            var created =
                    hostSession.createLobby(new CreateLobbyRequest("bot-0")).getBody();
            if (created == null || !host.playerId().equals(created.getHostPlayerId())) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT, "The lobby host is not the configured seat 0 bot");
            }
            return created.getLobbyId();
        } catch (RestClientException e) {
            // A lost lobby response cannot be recovered by identity; the attempt is retried on a clean lane.
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED, "The lobby could not be created: " + e.getMessage(), e);
        }
    }

    private void join(UUID lobbyId, LaneEndpoints.BotIdentity bot, int seatIndex, SessionApi hostSession) {
        var botSession = clients.create(SessionApi.class, lane.gameServiceUrl(), bot.token());
        try {
            var joined = botSession
                    .joinLobby(lobbyId, new JoinLobbyRequest("bot-" + seatIndex))
                    .getBody();
            if (joined == null || !bot.playerId().equals(joined.getPlayerId())) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT, "Seat " + seatIndex + " joined as another player");
            }
        } catch (RestClientException e) {
            // A lost acknowledgement may still have joined the seat; only then is the failure not one.
            if (!isMember(hostSession, lobbyId, bot)) {
                throw new AttemptFailedException(
                        FailureCode.EXECUTION_FAILED,
                        "Seat " + seatIndex + " could not join the lobby: " + e.getMessage(),
                        e);
            }
        }
    }

    private boolean isMember(SessionApi hostSession, UUID lobbyId, LaneEndpoints.BotIdentity bot) {
        try {
            var lobby = hostSession.getLobby(lobbyId).getBody();
            return lobby != null
                    && lobby.getMembers().stream()
                            .anyMatch(member -> member.getPlayerId().equals(bot.playerId()));
        } catch (RestClientException _) {
            return false;
        }
    }

    /** Starts the game; a lost response is reconciled against the lobby's start state, never resent blindly. */
    private UUID start(SessionApi hostSession, UUID lobbyId) {
        try {
            var started = hostSession.startGame(lobbyId).getBody();
            if (started != null) {
                return started.getGameId();
            }
        } catch (RestClientResponseException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED, "The game was refused: " + ProblemCodes.codeOf(e), e);
        } catch (RestClientException _) {
            // The response was lost; the lobby tells whether the start was accepted.
        }
        var reconciling = pacing.waits().now();
        while (pacing.waits().now() - reconciling < START_RECONCILE_BUDGET.toNanos()) {
            try {
                var lobby = hostSession.getLobby(lobbyId).getBody();
                if (lobby != null && lobby.getStatus() == LobbyStatus.STARTED) {
                    return lobby.getGameId();
                }
            } catch (RestClientException _) {
                // Try again after the pause.
            }
            pacing.sleeper().sleep(pacing.barrier().pollInterval());
        }
        throw new AttemptFailedException(
                FailureCode.EXECUTION_FAILED, "The outcome of starting lobby " + lobbyId + " could not be confirmed");
    }
}
