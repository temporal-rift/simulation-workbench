package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.Provenances;

class HttpLaneResetsTest {

    private static final UUID CASE_KEY = new UUID(2, 2);
    private static final UUID IDEMPOTENCY_KEY = new UUID(9, 9);

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int status = 204;
    private volatile boolean stalled;
    private final CountDownLatch released = new CountDownLatch(1);

    private record Received(
            String method, String path, String authorization, String contentType, String key, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stop() {
        released.countDown();
        server.stop(0);
    }

    @Test
    void aResetPostsTheLaneAndItsCaseWithTheOperatorTokenAndIdempotencyKey() {
        resets(Duration.ofSeconds(5)).reset("lane-1", CASE_KEY, IDEMPOTENCY_KEY);

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/lanes/lane-1/reset");
            assertThat(request.authorization()).isEqualTo("Bearer operator-token");
            assertThat(request.contentType()).startsWith("application/json");
            assertThat(request.key()).isEqualTo(IDEMPOTENCY_KEY.toString());
            assertThat(request.body()).isEqualTo("{\"laneId\":\"lane-1\",\"caseKey\":\"" + CASE_KEY + "\"}");
        });
    }

    @Test
    void aResetWithoutAKnownCaseOmitsTheCaseKey() {
        resets(Duration.ofSeconds(5)).reset("lane-1", null, IDEMPOTENCY_KEY);

        assertThat(received)
                .singleElement()
                .satisfies(request -> assertThat(request.body()).isEqualTo("{\"laneId\":\"lane-1\"}"));
    }

    @Test
    void anyTwoHundredAnswerMeansTheLaneIsClean() {
        status = 200;

        resets(Duration.ofSeconds(5)).reset("lane-1", CASE_KEY, IDEMPOTENCY_KEY);

        assertThat(received).hasSize(1);
    }

    @Test
    void aNonTwoHundredAnswerFailsWithItsStatus() {
        status = 503;

        var resets = resets(Duration.ofSeconds(5));

        assertThatThrownBy(() -> resets.reset("lane-1", CASE_KEY, IDEMPOTENCY_KEY))
                .hasMessage("status 503");
    }

    @Test
    void aRedirectDoesNotMeanTheLaneIsClean() {
        status = 307;

        var resets = resets(Duration.ofSeconds(5));

        assertThatThrownBy(() -> resets.reset("lane-1", CASE_KEY, IDEMPOTENCY_KEY))
                .hasMessage("status 307");
    }

    @Test
    void aResetSlowerThanTheResetTimeoutFails() {
        stalled = true;

        var resets = resets(Duration.ofMillis(200));

        assertThatThrownBy(() -> resets.reset("lane-1", CASE_KEY, IDEMPOTENCY_KEY))
                .isInstanceOf(ResetTimeoutException.class);
    }

    @Test
    void aLaneWithoutAResetHookCannotBeReset() {
        var resets = resets(Duration.ofSeconds(5));

        assertThatThrownBy(() -> resets.reset("lane-2", CASE_KEY, IDEMPOTENCY_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpLaneResets resets(Duration resetTimeout) {
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpLaneResets(
                List.of(lane("lane-1", URI.create(base + "/lanes/lane-1/reset")), lane("lane-2", null)),
                Duration.ofSeconds(1),
                resetTimeout);
    }

    private void answer(HttpExchange exchange) throws IOException {
        var headers = exchange.getRequestHeaders();
        received.add(new Received(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                headers.getFirst("Authorization"),
                headers.getFirst("Content-Type"),
                headers.getFirst("Idempotency-Key"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        if (stalled) {
            awaitRelease();
        }
        if (status == 307) {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /** Holds the answer back until the test ends, so the call outlives any timeout under test. */
    private void awaitRelease() {
        try {
            released.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static LaneEndpoints lane(String id, URI resetUrl) {
        return new LaneEndpoints(
                id,
                "http://game",
                "http://timeline",
                "http://read",
                "operator-token",
                new BundleDigests("a".repeat(64), "b".repeat(64)),
                Provenances.sample(),
                "game.events",
                "timeline.events",
                null,
                resetUrl,
                List.of());
    }
}
