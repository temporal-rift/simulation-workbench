package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.io.FileSystemResource;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RestApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.Faction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.SimulationSeat;
import io.github.temporalrift.workbench.execution.simulator.SimulatorBuilds;

/**
 * Starts the real executable jars as a slot hosts them. {@code simulator.builds} names the directory holding
 * {@code game-service.jar}, {@code timeline-service.jar} and {@code read-service.jar}.
 */
@EnabledIfSystemProperty(named = "simulator.builds", matches = ".+")
class SlotRuntimeRealJarsIT {

    @Test
    void theThreeRealServicesStartTogetherAndEachAnswersItsHealthEndpoint() throws Exception {
        try (var runtime = SlotRuntime.start(build(), 3, Clock.systemUTC())) {
            for (var url : List.of(runtime.gameServiceUrl(), runtime.timelineServiceUrl(), runtime.readServiceUrl())) {
                var health = HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(url + "/actuator/health"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).as(url).isEqualTo(200);
            }
        }
    }

    @Test
    void aSlotStartedAgainAcceptsTheNextCasesExecutionContext() {
        var build = build();

        try (var first = SlotRuntime.start(build, 3, Clock.systemUTC())) {
            configure(first);
        }
        try (var second = SlotRuntime.start(build, 3, Clock.systemUTC())) {
            configure(second);
        }
    }

    private static void configure(SlotRuntime runtime) {
        var factions = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);
        var context = new ExecutionContext()
                .schemaVersion(ExecutionContext.SchemaVersionEnum.NUMBER_1)
                .caseKey(UUID.randomUUID())
                .seed("42")
                .entropyVersion(ExecutionContext.EntropyVersionEnum.SHA256_V1)
                .manifestDigest("a".repeat(64))
                .logicalTime(Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC))
                .seats(IntStream.range(0, factions.size())
                        .mapToObj(seat -> new SimulationSeat(
                                seat, runtime.bots().get(seat).playerId(), factions.get(seat)))
                        .toList());
        var control = new RestApiClients(Duration.ofSeconds(5), Duration.ofSeconds(30))
                .create(SimulationExecutionApi.class, runtime.gameServiceUrl(), runtime.operatorToken());

        var checkpoint = control.configureSimulationExecution(context).getBody();

        assertThat(checkpoint).isNotNull();
        assertThat(checkpoint.getCaseKey()).isEqualTo(context.getCaseKey());
    }

    private static SlotRuntime.Build build() {
        var work = Path.of(System.getProperty("java.io.tmpdir"), "workbench-simulator-it");
        var rules = new CompositePropertySource("rules");
        try {
            new YamlPropertySourceLoader()
                    .load("rules", new FileSystemResource(SimulatorBuilds.rules()))
                    .forEach(rules::addPropertySource);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new SlotRuntime.Build(
                ServiceJar.open(SimulatorBuilds.jar("game-service"), work),
                ServiceJar.open(SimulatorBuilds.jar("timeline-service"), work),
                ServiceJar.open(SimulatorBuilds.jar("read-service"), work),
                rules);
    }
}
