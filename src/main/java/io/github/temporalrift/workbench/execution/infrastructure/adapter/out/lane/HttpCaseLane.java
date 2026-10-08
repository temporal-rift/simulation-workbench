package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.ActionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionCheckpoint;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.Faction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.SimulationSeat;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.scoring.ScoringApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.SessionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.CreateLobbyRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.JoinLobbyRequest;

/**
 * One isolated lane hosting one case at a time. Opening configures the case's execution context on both
 * services, then plays the lobby and start through the bots' own session operations; a recovering attempt
 * attaches to the game its predecessor started when the lane still hosts it.
 */
public class HttpCaseLane implements CaseLane {

    private static final int START_RECONCILE_POLLS = 5;
    /** Allowance for the difference between this clock and the producers' record timestamps. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final LaneEndpoints lane;
    private final ApiClients clients;
    private final CommandLedger ledger;
    private final EvidenceLedger evidence;
    private final GameEventObservers observers;
    private final List<GameEventObserver> opened = new CopyOnWriteArrayList<>();
    private final Clock clock;
    private final LaneEndpoints.Barrier barrier;
    private final Sleeper sleeper;
    private final Runnable release;

    public HttpCaseLane(
            LaneEndpoints lane,
            ApiClients clients,
            LaneServices services,
            Clock clock,
            LaneEndpoints.Barrier barrier,
            Sleeper sleeper,
            Runnable release) {
        this.lane = lane;
        this.clients = clients;
        this.ledger = services.commands();
        this.evidence = services.evidence();
        this.observers = services.observers();
        this.clock = clock;
        this.barrier = barrier;
        this.sleeper = sleeper;
        this.release = release;
    }

    @Override
    public String laneId() {
        return lane.id();
    }

    @Override
    public GameSession open(CaseContext context, UUID resumeGameId) {
        if (lane.bots().size() < context.seats().size()) {
            throw new AttemptFailedException(
                    FailureCode.CONTRACT_MISMATCH,
                    "Lane " + lane.id() + " has " + lane.bots().size() + " bot identities for "
                            + context.seats().size() + " seats");
        }
        var gameControl = clients.create(SimulationExecutionApi.class, lane.gameServiceUrl(), lane.operatorToken());
        var timelineControl =
                clients.create(SimulationExecutionApi.class, lane.timelineServiceUrl(), lane.operatorToken());
        var participants = participants(context);
        if (resumeGameId != null && hostsGame(gameControl, context, resumeGameId)) {
            var attached = session(context, resumeGameId, gameControl, timelineControl, participants, null);
            attached.recoverInDoubt();
            return attached.session();
        }
        // A new game: the slots of any abandoned game must neither block nor appear in this transcript.
        ledger.reset(context.caseId());
        var since = clock.instant().minus(CLOCK_SKEW);
        var gameId = startGame(context, gameControl, timelineControl);
        return session(context, gameId, gameControl, timelineControl, participants, since)
                .session();
    }

    @Override
    public void close() {
        try {
            opened.forEach(GameEventObserver::close);
            opened.clear();
        } finally {
            release.run();
        }
    }

    /** A game session with its durable gateway; recovery of in-doubt slots needs both. */
    private record Attached(HttpGameSession session, LedgerParticipantGateway gateway) {

        /** Resolves what a previous attempt left in doubt once the game has settled. */
        void recoverInDoubt() {
            if (session.awaitSettled()) {
                gateway.recoverInDoubt();
            }
        }
    }

    private Attached session(
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
                gameId, participants, () -> sessionRef.get().isSettled());
        var recorder = new StepRecorder(
                evidence,
                context.caseId(),
                gameId,
                context.attemptId(),
                context.seed(),
                () -> sessionRef.get().logicalTime());
        var durable =
                new LedgerParticipantGateway(raw, raw, ledger, recorder, context.caseId(), context.attemptId(), clock);
        var events = observers.open(lane, context.caseId(), context.attemptId(), gameId, since);
        opened.add(events);
        var session = new HttpGameSession(
                context,
                new HttpGameSession.ServiceControls(gameControl, timelineControl),
                raw,
                durable,
                scoring,
                new HttpGameSession.Pacing(barrier, sleeper),
                events);
        sessionRef.set(session);
        return new Attached(session, durable);
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

    /** Whether the lane still holds exactly this case's game, so the attempt can continue it. */
    private boolean hostsGame(SimulationExecutionApi gameControl, CaseContext context, UUID gameId) {
        try {
            var checkpoint = gameControl.getSimulationCheckpoint().getBody();
            return checkpoint != null
                    && context.caseKey().equals(checkpoint.getCaseKey())
                    && context.manifestDigest().equals(checkpoint.getManifestDigest())
                    && gameId.equals(checkpoint.getGameId());
        } catch (RestClientException _) {
            return false;
        }
    }

    private UUID startGame(
            CaseContext context, SimulationExecutionApi gameControl, SimulationExecutionApi timelineControl) {
        configure(gameControl, "game-service", context);
        configure(timelineControl, "timeline-service", context);
        var host = lane.bots().getFirst();
        var hostSession = clients.create(SessionApi.class, lane.gameServiceUrl(), host.token());
        var lobbyId = createLobby(hostSession, host);
        for (var seat : context.seats().subList(1, context.seats().size())) {
            var bot = lane.bots().get(seat.seatIndex());
            join(lobbyId, bot, seat.seatIndex(), hostSession);
        }
        return start(hostSession, lobbyId);
    }

    private void configure(SimulationExecutionApi control, String service, CaseContext context) {
        var seats = context.seats().stream()
                .map(seat -> new SimulationSeat(
                        seat.seatIndex(),
                        lane.bots().get(seat.seatIndex()).playerId(),
                        Faction.valueOf(seat.faction())))
                .toList();
        var request = new ExecutionContext()
                .schemaVersion(ExecutionContext.SchemaVersionEnum.NUMBER_1)
                .caseKey(context.caseKey())
                .seed(context.seed())
                .entropyVersion(ExecutionContext.EntropyVersionEnum.SHA256_V1)
                .manifestDigest(context.manifestDigest())
                .logicalTime(context.logicalEpoch().atOffset(java.time.ZoneOffset.UTC))
                .seats(seats);
        try {
            ExecutionCheckpoint checkpoint =
                    control.configureSimulationExecution(request).getBody();
            if (checkpoint == null
                    || !context.caseKey().equals(checkpoint.getCaseKey())
                    || !context.manifestDigest().equals(checkpoint.getManifestDigest())) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT, service + " did not adopt the case's execution context");
            }
        } catch (RestClientResponseException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    service + " refused the execution context: " + ProblemCodes.codeOf(e),
                    e);
        } catch (RestClientException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    service + " could not be configured for the case: " + e.getMessage(),
                    e);
        }
    }

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
        if (isMember(hostSession, lobbyId, bot)) {
            return;
        }
        try {
            var joined = botSession
                    .joinLobby(lobbyId, new JoinLobbyRequest("bot-" + seatIndex))
                    .getBody();
            if (joined == null || !bot.playerId().equals(joined.getPlayerId())) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT, "Seat " + seatIndex + " joined as another player");
            }
        } catch (RestClientException e) {
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
        for (var poll = 0; poll < START_RECONCILE_POLLS; poll++) {
            try {
                var lobby = hostSession.getLobby(lobbyId).getBody();
                if (lobby != null && "STARTED".equals(lobby.getStatus().name())) {
                    return lobby.getGameId();
                }
            } catch (RestClientException _) {
                // Try again after the pause.
            }
            sleeper.sleep(barrier.pollInterval());
        }
        throw new AttemptFailedException(
                FailureCode.EXECUTION_FAILED, "The outcome of starting lobby " + lobbyId + " could not be confirmed");
    }
}
