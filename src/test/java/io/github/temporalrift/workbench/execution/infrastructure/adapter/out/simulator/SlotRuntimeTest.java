package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;

import io.github.temporalrift.simulatorfixture.FakeServiceApplication;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints.BotIdentity;

/** Starts three stand-in services through the real hosting path: class loaders, parent context, profile, ports. */
class SlotRuntimeTest {

    private static final String FIXTURE = FakeServiceApplication.class.getName();

    @TempDir
    Path directory;

    private SlotRuntime runtime;

    @BeforeEach
    void clearDeliveries() {
        FakeServiceApplication.DELIVERED.clear();
    }

    @AfterEach
    void stopRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void hostsEachServiceOnItsOwnLoopbackPortWithItsOwnConfiguration() throws Exception {
        runtime = SlotRuntime.start(build("game", "timeline", "read"), 3, Clock.systemUTC());

        assertThat(runtime.gameServiceUrl()).startsWith("http://127.0.0.1:");
        assertThat(java.util.Set.of(runtime.gameServiceUrl(), runtime.timelineServiceUrl(), runtime.readServiceUrl()))
                .hasSize(3);
        assertThat(statusOf(runtime.gameServiceUrl() + "/anything")).isEqualTo(404);
        assertThat(statusOf(runtime.readServiceUrl() + "/anything")).isEqualTo(404);
    }

    @Test
    void mintsTheSameBotPlayersForEverySlotAndAnOperatorToken() {
        runtime = SlotRuntime.start(build("game", "timeline", "read"), 3, Clock.systemUTC());
        var other = SlotRuntime.start(build("game", "timeline", "read"), 3, Clock.systemUTC());
        try {
            assertThat(runtime.bots()).hasSize(3);
            assertThat(runtime.bots()).extracting(BotIdentity::playerId).doesNotHaveDuplicates();
            assertThat(other.bots())
                    .extracting(BotIdentity::playerId)
                    .isEqualTo(
                            runtime.bots().stream().map(BotIdentity::playerId).toList());
            assertThat(runtime.operatorToken()).isNotBlank().isNotEqualTo(other.operatorToken());
        } finally {
            other.close();
        }
    }

    @Test
    void routesAPublishedRecordToTheConsumingServicesOfItsTopic() {
        runtime = SlotRuntime.start(build("game", "timeline", "read"), 1, Clock.systemUTC());

        runtime.router().publish(SlotEventRouterTest.message(SlotEventRouter.GAME_EVENTS, "payload"));
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(runtime.router()::idle);

        assertThat(FakeServiceApplication.DELIVERED).containsExactlyInAnyOrder("timeline:payload", "read:payload");
    }

    @Test
    void aStartClassThatIsNotInTheJarFailsNamingIt() {
        var broken = new SlotRuntime.Build(
                open("game", FIXTURE),
                open("timeline", "io.github.temporalrift.missing.Main"),
                open("read", FIXTURE),
                rules());

        var clock = Clock.systemUTC();

        assertThatThrownBy(() -> SlotRuntime.start(broken, 1, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("io.github.temporalrift.missing.Main");
    }

    @Test
    void stoppingFreesThePorts() {
        runtime = SlotRuntime.start(build("game", "timeline", "read"), 1, Clock.systemUTC());
        var url = runtime.gameServiceUrl();

        runtime.close();
        runtime = null;

        assertThatThrownBy(() -> statusOf(url + "/anything")).isInstanceOf(IOException.class);
    }

    private SlotRuntime.Build build(String game, String timeline, String read) {
        return new SlotRuntime.Build(open(game, FIXTURE), open(timeline, FIXTURE), open(read, FIXTURE), rules());
    }

    private ServiceJar open(String name, String startClass) {
        var jar = FakeServiceJars.write(
                directory,
                name + startClass.hashCode() + ".jar",
                startClass,
                Map.of("application.yml", "fake:\n  name: " + name + "\n"));
        return ServiceJar.open(jar, directory.resolve("work"));
    }

    private static MapPropertySource rules() {
        return new MapPropertySource("rules", Map.of("game.rules.max-eras", "3"));
    }

    private static int statusOf(String url) throws IOException, InterruptedException {
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
