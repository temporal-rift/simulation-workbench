package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.simulatorfixture.FakeServiceApplication;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneWaits;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RestApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.ManualResetScheduler;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

/** Opens a lane whose stand-in services refuse every operation, which is enough to prove the slot is wired to them. */
class SimulatorCaseLaneTest {

    private static final BundleDigests BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final UUID CASE = new UUID(1, 1);

    @TempDir
    Path directory;

    private final ManualResetScheduler scheduler = new ManualResetScheduler();

    @Test
    void openingStartsTheSlotsServicesAndTheCaseIsRefusedByThem() {
        var pool = pool();
        pool.prepare();
        scheduler.runAll();
        var lane = pool.acquire(pool.freeLanes().get("simulator-1"), null).orElseThrow();

        assertThatThrownBy(() -> lane.open(context())).isInstanceOfSatisfying(AttemptFailedException.class, e -> {
            assertThat(e.failure().code()).isEqualTo(FailureCode.EXECUTION_FAILED);
            assertThat(e.getMessage()).contains("game-service refused the execution context");
        });

        lane.close();
        scheduler.runAll();
        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    private SimulatorLanePool pool() {
        var fixture = FakeServiceApplication.class.getName();
        var game = jar("game", fixture);
        var timeline = jar("timeline", fixture);
        var read = jar("read", fixture);
        var rules = directory.resolve("rules.properties");
        try {
            java.nio.file.Files.writeString(rules, "game.rules.max-eras=3");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        var declared = new SimulatorLanePool.Declared(
                new BundleProvenance.Artifact("c".repeat(64), null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                "1111111",
                "2222222",
                "3333333",
                new TreeMap<>(Map.of("session-api", "3.0.0")));
        return SimulatorLanePool.create(
                new SimulatorLanePool.Deployment(
                        1, game, timeline, read, rules, directory.resolve("work"), 6, BUNDLE, declared),
                new SimulatorLanePool.Collaborators(
                        new RestApiClients(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                        new InMemoryCommandLedger(),
                        new InMemoryEvidenceLedger(),
                        JsonMapper.builder().build(),
                        Clock.systemUTC(),
                        new LaneEndpoints.Barrier(Duration.ofMillis(1), Duration.ofSeconds(1)),
                        Sleeper.thread(),
                        new LaneWaits(new SimpleMeterRegistry()),
                        scheduler));
    }

    private Path jar(String name, String startClass) {
        return FakeServiceJars.write(
                directory, name + ".jar", startClass, Map.of("application.yml", "fake:\n  name: " + name + "\n"));
    }

    private static CaseLane.CaseContext context() {
        return new CaseLane.CaseContext(
                CASE,
                new UUID(5, 5),
                new UUID(6, 6),
                "42",
                "f".repeat(64),
                new CaseRules(new TreeMap<>(), "e".repeat(64)),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
