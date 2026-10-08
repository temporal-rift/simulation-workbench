package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.ActionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.ActionType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.CardActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.HandSelectionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.model.PassActionRequest;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ClockAdvance;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionState;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.Faction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.SimulationSeat;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.SessionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.CreateLobbyRequest;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;

/**
 * Exercises the generated clients over real HTTP against hand-written contract JSON, so wire shapes,
 * credentials and error handling are proven rather than assumed.
 */
class RestApiClientsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID GAME = new UUID(0, 0xA1);
    private static final UUID PLAYER = new UUID(0, 1);

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Recorded> last = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String responseBody = "{}";
    private volatile long delayMillis;
    private final RestApiClients clients = new RestApiClients(Duration.ofSeconds(2), Duration.ofSeconds(5));
    private final RestApiClients impatient = new RestApiClients(Duration.ofSeconds(2), Duration.ofMillis(300));

    private record Recorded(String method, String path, String authorization, String body) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theProjectionStateIsReadAsTheBotAndParsedFromTheContractJson() {
        responseBody = """
                {
                  "gameId": "%s", "eraNumber": 2, "winScoreThreshold": 20, "revision": 7,
                  "phase": "HAND_SELECTION", "roundNumber": null,
                  "myFaction": "ERASERS",
                  "myHand": [],
                  "pendingHandSelection": {
                    "cards": [
                      {"cardInstanceId": "%s", "cardType": "PUSH", "grade": "II", "dealSlot": 1}
                    ],
                    "requiredSelectionCount": 5,
                    "expiresAt": "2026-01-01T00:00:30Z"
                  },
                  "myScore": 0,
                  "myRevealedIntel": [],
                  "activeEvents": [
                    {"eventId": "%s", "title": "Storm", "carryOverState": "FRESH",
                     "outcomes": [{"outcomeId": "%s", "description": "calm", "initialProbability": 40}]}
                  ],
                  "players": [{"playerId": "%s", "score": 0, "isConnected": true}],
                  "mySubmissions": [
                    {"eraNumber": 1, "roundNumber": 2, "window": "ACTION", "status": "ACCEPTED", "choice": "PASS"}
                  ]
                }
                """.formatted(GAME, new UUID(0, 100), new UUID(0, 5), new UUID(0, 6), PLAYER);
        var projection = clients.create(ProjectionApi.class, baseUrl, "bot-token");

        var state = projection.getGameState(GAME).getBody();

        assertThat(last.get().method()).isEqualTo("GET");
        assertThat(last.get().path()).isEqualTo("/api/v1/games/" + GAME + "/state");
        assertThat(last.get().authorization()).isEqualTo("Bearer bot-token");
        assertThat(state.getPhase().name()).isEqualTo("HAND_SELECTION");
        assertThat(state.getRevision()).isEqualTo(7);
        assertThat(state.getPendingHandSelection().getCards()).hasSize(1);
        assertThat(state.getPendingHandSelection().getExpiresAt())
                .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:30Z"));
        assertThat(state.getMySubmissions().getFirst().getChoice().name()).isEqualTo("PASS");
        assertThat(ObservationMapper.owed(0, PLAYER, state)).isPresent();
    }

    @Test
    void handSelectionAndActionsAreSentInThePublishedWireShape() throws Exception {
        responseBody = "{\"gameId\":\"%s\",\"eraNumber\":1,\"playerId\":\"%s\",\"status\":\"SELECTED\"}"
                .formatted(GAME, PLAYER);
        var action = clients.create(ActionApi.class, baseUrl, "bot-token");

        action.selectHand(GAME, 1, new HandSelectionRequest(Set.of(new UUID(0, 1), new UUID(0, 2))));
        var hand = JSON.readTree(last.get().body());
        assertThat(last.get().path()).isEqualTo("/api/v1/games/" + GAME + "/eras/1/hand-selection");
        assertThat(hand.get("keptCardInstanceIds")).hasSize(2);

        responseBody = ("{\"gameId\":\"%s\",\"eraNumber\":1,\"roundNumber\":2,\"playerId\":\"%s\","
                        + "\"status\":\"SUBMITTED\",\"roundClosed\":false}")
                .formatted(GAME, PLAYER);
        action.submitAction(
                GAME,
                1,
                2,
                new CardActionRequest(new UUID(0, 7), ActionType.CARD)
                        .targetEventId(new UUID(0, 8))
                        .targetOutcomeId(new UUID(0, 9)));
        var card = JSON.readTree(last.get().body());
        assertThat(last.get().path()).isEqualTo("/api/v1/games/" + GAME + "/eras/1/rounds/2/actions");
        assertThat(card.get("actionType").asString()).isEqualTo("CARD");
        assertThat(card.get("cardInstanceId").asString()).isEqualTo(new UUID(0, 7).toString());
        assertThat(card.get("targetEventId").asString()).isEqualTo(new UUID(0, 8).toString());
        assertThat(card.has("targetPlayerId")).isFalse();

        action.submitAction(GAME, 1, 2, new PassActionRequest(ActionType.PASS));
        var pass = JSON.readTree(last.get().body());
        assertThat(pass.get("actionType").asString()).isEqualTo("PASS");
        assertThat(pass.size()).isEqualTo(1);
    }

    @Test
    void theExecutionContextIsSentAsTheControlContractRequires() throws Exception {
        responseBody = checkpointJson(null, "READY", true, null);
        var control = clients.create(SimulationExecutionApi.class, baseUrl, "operator-token");
        var caseKey = new UUID(1, 1);
        var request = new ExecutionContext()
                .schemaVersion(ExecutionContext.SchemaVersionEnum.NUMBER_1)
                .caseKey(caseKey)
                .seed("18446744073709551615")
                .entropyVersion(ExecutionContext.EntropyVersionEnum.SHA256_V1)
                .manifestDigest("a".repeat(64))
                .logicalTime(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .seats(List.of(new SimulationSeat(0, PLAYER, Faction.ERASERS)));

        var checkpoint = control.configureSimulationExecution(request).getBody();

        assertThat(last.get().method()).isEqualTo("PUT");
        assertThat(last.get().path()).isEqualTo("/internal/simulation/v1/execution");
        assertThat(last.get().authorization()).isEqualTo("Bearer operator-token");
        JsonNode body = JSON.readTree(last.get().body());
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.get("seed").asString()).isEqualTo("18446744073709551615");
        assertThat(body.get("entropyVersion").asString()).isEqualTo("SHA256_V1");
        assertThat(body.get("logicalTime").asString()).startsWith("2026-01-01T00:00:00");
        assertThat(body.get("seats").get(0).get("faction").asString()).isEqualTo("ERASERS");
        assertThat(checkpoint.getCaseKey()).isEqualTo(caseKey);
        assertThat(checkpoint.getState()).isEqualTo(ExecutionState.READY);
        assertThat(checkpoint.getGameId()).isNull();
        assertThat(checkpoint.getNextDeadline()).isNull();
        assertThat(checkpoint.getDrained()).isTrue();
    }

    @Test
    void theClockAdvanceCarriesItsOperationIdentityAndRevision() throws Exception {
        responseBody = "{\"operationId\":\"%s\",\"appliedRevision\":4,\"logicalTime\":\"2026-01-01T00:01:00Z\"}"
                .formatted(new UUID(2, 2));
        var control = clients.create(SimulationExecutionApi.class, baseUrl, "operator-token");

        var ack = control.advanceSimulationClock(new ClockAdvance()
                        .operationId(new UUID(2, 2))
                        .expectedRevision(3L)
                        .targetTime(OffsetDateTime.parse("2026-01-01T00:01:00Z")))
                .getBody();

        var body = JSON.readTree(last.get().body());
        assertThat(body.get("operationId").asString()).isEqualTo(new UUID(2, 2).toString());
        assertThat(body.get("expectedRevision").asLong()).isEqualTo(3L);
        assertThat(ack.getAppliedRevision()).isEqualTo(4L);
    }

    @Test
    void aProblemResponseSurfacesItsPublishedCode() {
        status = 409;
        contentType = "application/problem+json";
        responseBody = "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"x\","
                + "\"code\":\"EXECUTION_CONTEXT_CONFLICT\"}";
        var control = clients.create(SimulationExecutionApi.class, baseUrl, "operator-token");

        assertThatThrownBy(() -> control.configureSimulationExecution(new ExecutionContext()))
                .isInstanceOfSatisfying(
                        RestClientResponseException.class,
                        e -> assertThat(ProblemCodes.codeOf(e)).isEqualTo("EXECUTION_CONTEXT_CONFLICT"));
    }

    @Test
    void aRejectedParticipantCommandIsDefinitiveButAnUnansweredOneIsNot() {
        var session = clients.create(SessionApi.class, baseUrl, "bot-token");
        var impatientSession = impatient.create(SessionApi.class, baseUrl, "bot-token");
        status = 422;
        contentType = "application/problem+json";
        responseBody = "{\"detail\":\"too few\",\"code\":\"422-02\"}";

        assertThatThrownBy(() -> session.startGame(GAME))
                .isInstanceOfSatisfying(
                        RestClientResponseException.class,
                        e -> assertThat(ProblemCodes.outcomeOf(e)).isEqualTo(new SubmissionOutcome.Rejected("422-02")));

        status = 200;
        responseBody = "{}";
        delayMillis = 1500;
        assertThatThrownBy(() -> impatientSession.createLobby(new CreateLobbyRequest("bot")))
                .isInstanceOfSatisfying(
                        ResourceAccessException.class,
                        e -> assertThat(ProblemCodes.outcomeOf(e))
                                .isInstanceOf(SubmissionOutcome.Unacknowledged.class));
    }

    private static String checkpointJson(UUID gameId, String state, boolean drained, String nextDeadline) {
        return """
                {"caseKey":"%s","manifestDigest":"%s","revision":0,"logicalTime":"2026-01-01T00:00:00Z",
                 "gameId":%s,"state":"%s","drained":%s,"outboxPending":0,"continuationsPending":0,
                 "dueTimersPending":0,"nextDeadline":%s,"sourceWatermarks":[]}
                """.formatted(
                        new UUID(1, 1),
                        "a".repeat(64),
                        gameId == null ? "null" : "\"" + gameId + "\"",
                        state,
                        drained,
                        nextDeadline == null ? "null" : "\"" + nextDeadline + "\"");
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        last.set(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
        try {
            new CountDownLatch(1).await(delayMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException _) {
            // The client gave up on a delayed response.
        }
    }
}
