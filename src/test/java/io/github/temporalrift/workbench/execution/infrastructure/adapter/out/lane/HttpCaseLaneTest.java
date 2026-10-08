package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionCheckpoint;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionState;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Phase;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionWindow;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.SessionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.CreateLobbyResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.JoinLobbyResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.LobbyResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.LobbyStatus;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.PlayerInLobby;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.StartGameResponse;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.StubEventObserver;

class HttpCaseLaneTest {

    private static final UUID CASE_ID = new UUID(1, 1);
    private static final UUID CASE_KEY = new UUID(2, 2);
    private static final UUID LOBBY = new UUID(3, 3);
    private static final UUID GAME = ProjectionStates.GAME;
    private static final String DIGEST = "c".repeat(64);
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private final FakeClients clients = new FakeClients();
    private final InMemoryCommandLedger ledger = new InMemoryCommandLedger();
    private final InMemoryEvidenceLedger evidence = new InMemoryEvidenceLedger();
    private final StubEventObserver events = new StubEventObserver();
    private final AtomicInteger released = new AtomicInteger();
    private SimulationExecutionApi gameControl;
    private SimulationExecutionApi timelineControl;
    private LaneEndpoints endpoints;
    private HttpCaseLane lane;

    @BeforeEach
    void setUp() {
        endpoints = new LaneEndpoints(
                "lane-1",
                "http://game",
                "http://timeline",
                "http://read",
                "operator-token",
                "game.events",
                "timeline.events",
                List.of(
                        new LaneEndpoints.BotIdentity(ProjectionStates.id(1), "t1"),
                        new LaneEndpoints.BotIdentity(ProjectionStates.id(2), "t2"),
                        new LaneEndpoints.BotIdentity(ProjectionStates.id(3), "t3")));
        gameControl = clients.control("http://game");
        timelineControl = clients.control("http://timeline");
        lane = lane(endpoints);
    }

    @Test
    void aFreshStartConfiguresBothServicesAndThenPlaysTheLobbyAsTheBots() {
        ledger.put(new SlotId(CASE_ID, 0, "era1/hand-selection"), new UUID(9, 9), "old", SlotStatus.ACCEPTED);
        configured();
        lobby(new HostedLobby());

        var session = lane.open(context(), null);

        assertThat(session.gameId()).isEqualTo(GAME);
        var requests = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(gameControl).configureSimulationExecution(requests.capture());
        verify(timelineControl).configureSimulationExecution(any());
        var request = requests.getValue();
        assertThat(request.getCaseKey()).isEqualTo(CASE_KEY);
        assertThat(request.getSeed()).isEqualTo("42");
        assertThat(request.getManifestDigest()).isEqualTo(DIGEST);
        assertThat(request.getLogicalTime()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThat(request.getEntropyVersion()).isEqualTo(ExecutionContext.EntropyVersionEnum.SHA256_V1);
        assertThat(request.getSeats())
                .extracting(seat -> seat.getSeatIndex() + ":" + seat.getPlayerId() + ":"
                        + seat.getFaction().name())
                .containsExactly(
                        "0:" + ProjectionStates.id(1) + ":ERASERS",
                        "1:" + ProjectionStates.id(2) + ":PROPHETS",
                        "2:" + ProjectionStates.id(3) + ":WEAVERS");
        verify(clients.session("t2")).joinLobby(any(), any());
        verify(clients.session("t3")).joinLobby(any(), any());
        verify(clients.session("t1")).startGame(LOBBY);
        assertThat(ledger.find(new SlotId(CASE_ID, 0, "era1/hand-selection"))).isEmpty();
    }

    @Test
    void aLostStartResponseIsReconciledAgainstTheLobbyAndNeverStartedTwice() {
        configured();
        var hosted = new HostedLobby();
        lobby(hosted);
        when(clients.session("t1").startGame(LOBBY)).thenThrow(new ResourceAccessException("reset"));
        hosted.status = LobbyStatus.STARTED;

        var session = lane.open(context(), null);

        assertThat(session.gameId()).isEqualTo(GAME);
        verify(clients.session("t1"), times(1)).startGame(LOBBY);
    }

    @Test
    void aStartThatNeverShowsAStartedLobbyFailsTheAttempt() {
        configured();
        lobby(new HostedLobby());
        when(clients.session("t1").startGame(LOBBY)).thenThrow(new ResourceAccessException("reset"));

        assertThatThrownBy(() -> lane.open(context(), null)).isInstanceOfSatisfying(AttemptFailedException.class, e -> {
            assertThat(e.failure().code()).isEqualTo(FailureCode.EXECUTION_FAILED);
            assertThat(e.failure().message()).contains("could not be confirmed");
        });
    }

    @Test
    void aRefusedStartIsAFailureNotAReconciliation() {
        configured();
        lobby(new HostedLobby());
        when(clients.session("t1").startGame(LOBBY))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.CONFLICT,
                        "",
                        new HttpHeaders(),
                        "{\"code\":\"409-03\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        assertThatThrownBy(() -> lane.open(context(), null))
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().message()).contains("409-03"));
    }

    @Test
    void aBotThatAlreadyJoinedIsNotJoinedAgain() {
        configured();
        var hosted = new HostedLobby();
        hosted.members.add(new PlayerInLobby(ProjectionStates.id(2), "bot-1", false));
        lobby(hosted);

        lane.open(context(), null);

        verify(clients.session("t2"), never()).joinLobby(any(), any());
        verify(clients.session("t3")).joinLobby(any(), any());
    }

    @Test
    void aLobbyHostThatIsNotTheSeatZeroBotIsConfigurationDrift() {
        configured();
        var hosted = new HostedLobby();
        lobby(hosted);
        when(clients.session("t1").createLobby(any()))
                .thenReturn(ResponseEntity.ok(new CreateLobbyResponse(LOBBY, ProjectionStates.id(77), "JOIN")));

        assertThatThrownBy(() -> lane.open(context(), null))
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT));
    }

    @Test
    void aServiceThatRefusesTheExecutionContextFailsTheAttemptWithItsCode() {
        when(gameControl.configureSimulationExecution(any()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.CONFLICT,
                        "",
                        new HttpHeaders(),
                        "{\"code\":\"EXECUTION_CONTEXT_CONFLICT\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        assertThatThrownBy(() -> lane.open(context(), null)).isInstanceOfSatisfying(AttemptFailedException.class, e -> {
            assertThat(e.failure().code()).isEqualTo(FailureCode.EXECUTION_FAILED);
            assertThat(e.failure().message()).contains("EXECUTION_CONTEXT_CONFLICT");
        });
        verify(clients.session("t1"), never()).createLobby(any());
    }

    @Test
    void aServiceThatAdoptsAnotherManifestIsConfigurationDrift() {
        when(gameControl.configureSimulationExecution(any()))
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, "d".repeat(64), null)));

        assertThatThrownBy(() -> lane.open(context(), null))
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT));
    }

    @Test
    void aRecoveringAttemptAttachesToTheGameTheLaneStillHostsAndResolvesWhatWasInDoubt() {
        when(gameControl.getSimulationCheckpoint()).thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, GAME)));
        when(timelineControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, GAME)));
        for (var seat = 1; seat <= 3; seat++) {
            var state = ProjectionStates.base(Phase.RESOLUTION, 1);
            if (seat == 1) {
                state.getMySubmissions().add(ProjectionStates.accepted(SubmissionWindow.HAND_SELECTION, 1, null));
            }
            when(clients.projection("t" + seat).getGameState(GAME)).thenReturn(ResponseEntity.ok(state));
        }
        ledger.put(new SlotId(CASE_ID, 0, "era1/hand-selection"), new UUID(9, 9), "kept", SlotStatus.SENT);

        var session = lane.open(context(), GAME);

        assertThat(session.gameId()).isEqualTo(GAME);
        verify(gameControl, never()).configureSimulationExecution(any());
        verify(clients.session("t1"), never()).createLobby(any());
        assertThat(ledger.find(new SlotId(CASE_ID, 0, "era1/hand-selection"))
                        .orElseThrow()
                        .status())
                .isEqualTo(SlotStatus.ACCEPTED);
    }

    @Test
    void aLaneThatHostsAnotherGameIsStartedAfresh() {
        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, new UUID(8, 8))));
        configured();
        lobby(new HostedLobby());

        lane.open(context(), GAME);

        verify(gameControl).configureSimulationExecution(any());
    }

    @Test
    void aLaneWithoutEnoughBotsCannotHostTheCase() {
        var small = lane(new LaneEndpoints(
                "lane-2",
                "http://game",
                "http://timeline",
                "http://read",
                "operator-token",
                "game.events",
                "timeline.events",
                List.of(new LaneEndpoints.BotIdentity(ProjectionStates.id(1), "t1"))));

        assertThatThrownBy(() -> small.open(context(), null))
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONTRACT_MISMATCH));
    }

    @Test
    void closingTheLaneReleasesIt() {
        lane.close();

        assertThat(released.get()).isEqualTo(1);
        assertThat(lane.laneId()).isEqualTo("lane-1");
    }

    private HttpCaseLane lane(LaneEndpoints endpoints) {
        return new HttpCaseLane(
                endpoints,
                clients,
                ledger,
                evidence,
                (_, _, _, _) -> events,
                Clock.fixed(EPOCH, ZoneOffset.UTC),
                new LaneEndpoints.Barrier(Duration.ZERO, 1, 2),
                _ -> {},
                released::incrementAndGet);
    }

    private CaseLane.CaseContext context() {
        return new CaseLane.CaseContext(
                CASE_ID,
                new UUID(4, 4),
                CASE_KEY,
                "42",
                DIGEST,
                List.of(
                        new SeatPlan(0, "ERASERS", "random", "1.0.0"),
                        new SeatPlan(1, "PROPHETS", "random", "1.0.0"),
                        new SeatPlan(2, "WEAVERS", "random", "1.0.0")),
                EPOCH);
    }

    private void configured() {
        when(gameControl.configureSimulationExecution(any()))
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, null)));
        when(timelineControl.configureSimulationExecution(any()))
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, null)));
    }

    /** The lobby as the host's session sees it; tests flip its state to model what the service did. */
    private static final class HostedLobby {
        final List<PlayerInLobby> members =
                new ArrayList<>(List.of(new PlayerInLobby(ProjectionStates.id(1), "bot-0", true)));
        LobbyStatus status = LobbyStatus.WAITING;
    }

    private void lobby(HostedLobby hosted) {
        var host = clients.session("t1");
        when(host.createLobby(any()))
                .thenReturn(ResponseEntity.ok(new CreateLobbyResponse(LOBBY, ProjectionStates.id(1), "JOIN")));
        when(host.getLobby(LOBBY))
                .thenAnswer(_ -> ResponseEntity.ok(new LobbyResponse(
                        LOBBY, GAME, ProjectionStates.id(1), hosted.status, List.copyOf(hosted.members))));
        when(host.startGame(LOBBY)).thenReturn(ResponseEntity.accepted().body(new StartGameResponse(GAME)));
        for (var seat = 2; seat <= 3; seat++) {
            var playerId = ProjectionStates.id(seat);
            when(clients.session("t" + seat).joinLobby(any(), any())).thenAnswer(_ -> {
                hosted.members.add(new PlayerInLobby(playerId, "bot", false));
                return ResponseEntity.ok(new JoinLobbyResponse(LOBBY, playerId, List.of()));
            });
        }
    }

    private static ExecutionCheckpoint checkpoint(UUID caseKey, String digest, UUID gameId) {
        return new ExecutionCheckpoint()
                .caseKey(caseKey)
                .manifestDigest(digest)
                .revision(0L)
                .logicalTime(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .gameId(gameId)
                .state(gameId == null ? ExecutionState.READY : ExecutionState.ACTIVE)
                .drained(true)
                .outboxPending(0)
                .continuationsPending(0)
                .dueTimersPending(0);
    }

    /** Hands out one mock per service and credential, so tests can tell the bots and the operator apart. */
    private static final class FakeClients implements ApiClients {

        private final Map<String, Object> created = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T create(Class<T> api, String baseUrl, String bearerToken) {
            return (T) created.computeIfAbsent(api.getSimpleName() + "|" + baseUrl + "|" + bearerToken, _ -> mock(api));
        }

        SimulationExecutionApi control(String url) {
            return create(SimulationExecutionApi.class, url, "operator-token");
        }

        SessionApi session(String token) {
            return create(SessionApi.class, "http://game", token);
        }

        ProjectionApi projection(String token) {
            return create(ProjectionApi.class, "http://read", token);
        }
    }
}
