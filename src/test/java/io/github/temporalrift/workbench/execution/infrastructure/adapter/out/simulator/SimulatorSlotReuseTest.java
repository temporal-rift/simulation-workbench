package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.simulatorfixture.FakeControlledServiceApplication;
import io.github.temporalrift.simulatorfixture.FakeServiceApplication;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.LaneNotCleanException;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneWaits;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RestApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.ManualResetScheduler;

/**
 * A slot keeps its services across cases, closes each case through their control interface, and restarts them only to
 * recover: after a failed close or a service that still holds an earlier case. Its idle services stop.
 */
class SimulatorSlotReuseTest {

    private static final BundleDigests BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final String CONTROLLED = FakeControlledServiceApplication.class.getName();
    private static final String UNCONTROLLED = FakeServiceApplication.class.getName();

    @TempDir
    Path directory;

    private final ManualResetScheduler scheduler = new ManualResetScheduler();
    private SimulatorSlot slot;
    private SimulatorLanePool pool;

    @AfterEach
    void stopServices() {
        if (slot != null) {
            slot.stop();
        }
        if (pool != null) {
            pool.slot("simulator-1").stop();
        }
    }

    @Test
    void aSlotKeepsItsServicesUntilItIsRestarted() {
        slot = slot(Duration.ZERO);

        var first = slot.runtime();
        slot.released();
        var second = slot.runtime();
        slot.restart();

        assertThat(second).isSameAs(first);
        assertThat(slot.running())
                .hasValueSatisfying(restarted -> assertThat(restarted).isNotSameAs(first));
    }

    @Test
    void anIdleSlotStopsItsServicesAndStartsThemForItsNextCase() {
        slot = slot(Duration.ofMinutes(10));
        var first = slot.runtime();

        slot.released();
        assertThat(scheduler.delays()).containsExactly(Duration.ofMinutes(10));
        scheduler.runAll();

        assertThat(slot.running()).isEmpty();
        assertThat(slot.runtime()).isNotSameAs(first);
    }

    @Test
    void aSlotThatTookAnotherCaseIsNotStoppedByAnEarlierIdleTimeout() {
        slot = slot(Duration.ofMinutes(10));
        var first = slot.runtime();
        slot.released();

        assertThat(slot.runtime()).isSameAs(first);
        scheduler.runAll();

        assertThat(slot.running()).containsSame(first);
    }

    @Test
    void consecutiveCasesPlayOnTheSameServicesAndEachIsClosedThroughThem() {
        pool = pool(CONTROLLED, Map.of());
        var closes = FakeControlledServiceApplication.CLOSES.get();

        var first = play();
        var second = play();

        assertThat(second).isSameAs(first);
        assertThat(FakeControlledServiceApplication.CLOSES.get() - closes)
                .as("each case closed on game, timeline and read")
                .isEqualTo(6);
        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    @Test
    void aFailedCloseRestartsTheSlotBeforeItTakesAnotherCase() {
        pool = pool(CONTROLLED, Map.of("read", "fake:\n  close-fails: true\n"));

        var first = play();

        assertThat(pool.slot("simulator-1").running())
                .hasValueSatisfying(restarted -> assertThat(restarted).isNotSameAs(first));
        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    @Test
    void aServiceHoldingAnEarlierCaseRestartsTheSlotWithoutFailingTheCase() {
        pool = pool(CONTROLLED, Map.of("game", "fake:\n  leftover: true\n"));
        pool.prepare();
        scheduler.runAll();
        var lane = pool.acquire(pool.freeLanes().get("simulator-1"), null).orElseThrow();

        var context = context();

        assertThatThrownBy(() -> lane.open(context))
                .isInstanceOf(LaneNotCleanException.class)
                .hasMessageContaining("game-service");
        var unclean = pool.slot("simulator-1").running().orElseThrow();
        lane.close();
        assertThat(pool.freeLanes())
                .as("the lane is not offered before its reset")
                .isEmpty();
        scheduler.runAll();

        assertThat(pool.slot("simulator-1").running())
                .hasValueSatisfying(restarted -> assertThat(restarted).isNotSameAs(unclean));
        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    @Test
    void aCaseThatNoServiceAcceptedStillLetsTheIdleSlotStop() {
        pool = pool(UNCONTROLLED, Map.of(), Duration.ofMinutes(10));
        pool.prepare();
        scheduler.runAll();
        var lane = pool.acquire(pool.freeLanes().get("simulator-1"), null).orElseThrow();
        var context = context();
        assertThatThrownBy(() -> lane.open(context)).isInstanceOf(AttemptFailedException.class);
        assertThat(pool.slot("simulator-1").running())
                .as("the refused case started the services")
                .isPresent();

        lane.close();
        scheduler.runAll();

        assertThat(pool.slot("simulator-1").running()).isEmpty();
        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    /** Plays one case, which the stand-ins end right after it is configured; returns the services it ran on. */
    private SlotRuntime play() {
        pool.prepare();
        scheduler.runAll();
        var lane = pool.acquire(pool.freeLanes().get("simulator-1"), null).orElseThrow();
        var context = context();
        assertThatThrownBy(() -> lane.open(context)).isInstanceOf(AttemptFailedException.class);
        var played = pool.slot("simulator-1").running().orElseThrow();
        lane.close();
        scheduler.runAll();
        return played;
    }

    private SimulatorSlot slot(Duration idleTimeout) {
        var build = new SlotRuntime.Build(
                open("game", UNCONTROLLED, Map.of()),
                open("timeline", UNCONTROLLED, Map.of()),
                open("read", UNCONTROLLED, Map.of()),
                new MapPropertySource("rules", Map.of("game.rules.max-eras", "3")));
        return new SimulatorSlot(
                "simulator-1", build, SimulatorBots.derived(3), Clock.systemUTC(), idleTimeout, scheduler);
    }

    private ServiceJar open(String name, String startClass, Map<String, String> configuration) {
        var yml = configuration.getOrDefault(name, "") + "fake:\n  name: " + name + "\n";
        var jar = FakeServiceJars.write(directory, name + ".jar", startClass, Map.of("application.yml", merge(yml)));
        return ServiceJar.open(jar, directory.resolve("work"));
    }

    /** Folds repeated {@code fake:} sections into one, so a service's extra settings sit beside its name. */
    private static String merge(String yml) {
        var settings = yml.lines().filter(line -> !line.equals("fake:")).toList();
        return "fake:\n" + String.join("\n", settings) + "\n";
    }

    private SimulatorLanePool pool(String startClass, Map<String, String> configuration) {
        return pool(startClass, configuration, Duration.ZERO);
    }

    private SimulatorLanePool pool(String startClass, Map<String, String> configuration, Duration idleTimeout) {
        var game = jarPath("game", startClass, configuration);
        var timeline = jarPath("timeline", startClass, configuration);
        var read = jarPath("read", startClass, configuration);
        Path rules;
        try {
            rules = Files.writeString(directory.resolve("rules.properties"), "game.rules.max-eras=3");
        } catch (IOException e) {
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
                        1,
                        game,
                        timeline,
                        read,
                        rules,
                        directory.resolve("work"),
                        SimulatorBots.derived(6),
                        idleTimeout,
                        BUNDLE,
                        declared),
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

    private Path jarPath(String name, String startClass, Map<String, String> configuration) {
        var yml = configuration.getOrDefault(name, "") + "fake:\n  name: " + name + "\n";
        return FakeServiceJars.write(directory, name + ".jar", startClass, Map.of("application.yml", merge(yml)));
    }

    private static CaseLane.CaseContext context() {
        return new CaseLane.CaseContext(
                new UUID(1, 1),
                new UUID(5, 5),
                new UUID(6, 6),
                "42",
                "f".repeat(64),
                new CaseRules(new TreeMap<>(), "e".repeat(64)),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
