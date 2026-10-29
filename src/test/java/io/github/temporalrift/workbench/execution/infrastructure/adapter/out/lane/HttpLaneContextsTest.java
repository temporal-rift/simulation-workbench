package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.Provenances;

class HttpLaneContextsTest {

    private static final UUID CASE_KEY = new UUID(2, 2);
    private static final UUID OPEN_KEY = new UUID(3, 3);

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer game;
    private HttpServer timeline;
    private HttpServer read;
    private volatile int gameStatus = 204;
    private volatile int timelineStatus = 204;
    private volatile int readStatus = 204;
    private volatile String gameBody = "";
    private volatile String checkpointBody = "";
    private volatile boolean stalled;
    private final CountDownLatch released = new CountDownLatch(1);

    private record Received(String service, String method, String path, String query, String authorization) {}

    @BeforeEach
    void start() throws IOException {
        game = server("game", () -> gameStatus, () -> gameBody);
        timeline = server("timeline", () -> timelineStatus, () -> "");
        read = server("read", () -> readStatus, () -> "");
    }

    @AfterEach
    void stop() {
        released.countDown();
        game.stop(0);
        timeline.stop(0);
        read.stop(0);
    }

    @Test
    void aCaseIsClosedOnEveryServiceWithTheOperatorTokenAndItsCaseKey() {
        contexts(Duration.ofSeconds(5))
                .close("lane-1", CASE_KEY, Set.of(LaneService.GAME, LaneService.TIMELINE, LaneService.READ));

        assertThat(received).hasSize(3).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/internal/simulation/v1/execution");
            assertThat(request.query()).isEqualTo("caseKey=" + CASE_KEY);
            assertThat(request.authorization()).isEqualTo("Bearer operator-token");
        });
        assertThat(received).extracting(Received::service).containsExactly("game", "timeline", "read");
    }

    @Test
    void aServiceTheContextWasNeverSentToIsNotAsked() {
        contexts(Duration.ofSeconds(5)).close("lane-1", CASE_KEY, Set.of(LaneService.GAME));

        assertThat(received).extracting(Received::service).containsExactly("game");
    }

    @Test
    void aServiceThatHoldsNoContextOfTheCaseIsNotAFailure() {
        gameStatus = 409;
        gameBody = "{\"code\":\"EXECUTION_NOT_CONFIGURED\"}";

        contexts(Duration.ofSeconds(5)).close("lane-1", CASE_KEY, Set.of(LaneService.GAME, LaneService.TIMELINE));

        assertThat(received).hasSize(2);
    }

    @Test
    void aConflictOfContextsIsAFailure() {
        gameStatus = 409;
        gameBody = "{\"code\":\"EXECUTION_CONTEXT_CONFLICT\"}";
        var contexts = contexts(Duration.ofSeconds(5));

        assertThatThrownBy(() -> contexts.close("lane-1", CASE_KEY, Set.of(LaneService.GAME)))
                .hasMessageContaining("game-service")
                .hasMessageContaining("EXECUTION_CONTEXT_CONFLICT");
    }

    @Test
    void aServerErrorIsAFailureAndTheOtherServiceIsStillAsked() {
        gameStatus = 500;
        var contexts = contexts(Duration.ofSeconds(5));

        assertThatThrownBy(() -> contexts.close("lane-1", CASE_KEY, Set.of(LaneService.GAME, LaneService.TIMELINE)))
                .hasMessageContaining("500");

        assertThat(received).extracting(Received::service).containsExactly("game", "timeline");
    }

    @Test
    void aServiceSlowerThanTheTimeoutFailsWithATimeout() {
        stalled = true;
        var contexts = contexts(Duration.ofMillis(200));

        var services = Set.of(LaneService.GAME);

        assertThatThrownBy(() -> contexts.close("lane-1", CASE_KEY, services))
                .isInstanceOf(CloseTimeoutException.class);
    }

    @Test
    void closingWhateverIsOpenClosesTheCaseEachServiceReportsAsOpen() {
        checkpointBody = checkpoint(OPEN_KEY);

        contexts(Duration.ofSeconds(5)).closeAnyOpen("lane-1");

        assertThat(received)
                .extracting(Received::service, Received::method, Received::query)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("game", "GET", null),
                        org.assertj.core.api.Assertions.tuple("game", "DELETE", "caseKey=" + OPEN_KEY),
                        org.assertj.core.api.Assertions.tuple("timeline", "GET", null),
                        org.assertj.core.api.Assertions.tuple("timeline", "DELETE", "caseKey=" + OPEN_KEY),
                        org.assertj.core.api.Assertions.tuple("read", "GET", null),
                        org.assertj.core.api.Assertions.tuple("read", "DELETE", "caseKey=" + OPEN_KEY));
    }

    @Test
    void closingWhateverIsOpenDoesNothingWhereNothingIsOpen() {
        gameStatus = 409;
        timelineStatus = 409;
        readStatus = 409;
        gameBody = "{\"code\":\"EXECUTION_NOT_CONFIGURED\"}";
        checkpointBody = gameBody;

        contexts(Duration.ofSeconds(5)).closeAnyOpen("lane-1");

        assertThat(received).extracting(Received::method).containsOnly("GET");
    }

    @Test
    void aLaneThatIsNotConfiguredCannotBeClosed() {
        var contexts = contexts(Duration.ofSeconds(5));

        var services = Set.of(LaneService.GAME);

        assertThatThrownBy(() -> contexts.close("lane-9", CASE_KEY, services))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpLaneContexts contexts(Duration timeout) {
        return new HttpLaneContexts(List.of(lane()), new RestApiClients(Duration.ofSeconds(1), timeout));
    }

    private LaneEndpoints lane() {
        var bundle = new BundleDigests("a".repeat(64), "b".repeat(64));
        return new LaneEndpoints(
                "lane-1",
                "http://127.0.0.1:" + game.getAddress().getPort(),
                "http://127.0.0.1:" + timeline.getAddress().getPort(),
                "http://127.0.0.1:" + read.getAddress().getPort(),
                "operator-token",
                bundle,
                Provenances.of(bundle),
                "game.events",
                "timeline.events",
                null,
                null,
                List.of());
    }

    private HttpServer server(
            String service, java.util.function.IntSupplier status, java.util.function.Supplier<String> body)
            throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> answer(service, exchange, status.getAsInt(), body.get()));
        server.setExecutor(null);
        server.start();
        return server;
    }

    private void answer(String service, HttpExchange exchange, int status, String body) throws IOException {
        received.add(new Received(
                service,
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Authorization")));
        if (stalled) {
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            var payload = checkpointBody.isEmpty() ? body : checkpointBody;
            respond(exchange, checkpointBody.startsWith("{\"caseKey\"") ? 200 : status, payload);
            return;
        }
        respond(exchange, status, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .add("Content-Type", status == 200 ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String checkpoint(UUID caseKey) {
        return "{\"caseKey\":\"" + caseKey + "\",\"manifestDigest\":\"" + "c".repeat(64) + "\",\"rulesDigest\":\""
                + "e".repeat(64) + "\",\"revision\":0,\"logicalTime\":\"2026-01-01T00:00:00Z\",\"gameId\":null,"
                + "\"state\":\"TERMINAL\",\"drained\":true,\"outboxPending\":0,\"continuationsPending\":0,"
                + "\"dueTimersPending\":0,\"nextDeadline\":null,\"sourceWatermarks\":[]}";
    }
}
