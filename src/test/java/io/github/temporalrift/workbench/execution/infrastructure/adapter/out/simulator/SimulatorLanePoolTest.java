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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneWaits;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.ManualResetScheduler;

class SimulatorLanePoolTest {

    private static final BundleDigests BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final UUID CASE = new UUID(1, 1);
    private static final UUID OTHER_CASE = new UUID(2, 2);
    private static final UUID THIRD_CASE = new UUID(3, 3);

    @TempDir
    Path directory;

    private final ManualResetScheduler scheduler = new ManualResetScheduler();
    private Path gameJar;
    private Path timelineJar;
    private Path readJar;
    private Path rules;

    @BeforeEach
    void writeServices() throws IOException {
        gameJar = FakeServiceJars.write(directory, "game.jar", "io.github.temporalrift.game.App", Map.of("g", "1"));
        timelineJar = FakeServiceJars.write(
                directory, "timeline.jar", "io.github.temporalrift.timeline.App", Map.of("t", "1"));
        readJar = FakeServiceJars.write(directory, "read.jar", "io.github.temporalrift.read.App", Map.of("r", "1"));
        rules = Files.writeString(directory.resolve("rules.yml"), "game:\n  rules:\n    max-eras: 3\n");
    }

    @Test
    void everySlotIsAFreeLaneOfTheOneBundleOnceThePoolIsPrepared() {
        var pool = pool(2);

        assertThat(pool.freeLanes()).isEmpty();
        pool.prepare(List.of());
        scheduler.runAll();

        assertThat(pool.freeLanes()).isEqualTo(Map.of("simulator-1", BUNDLE, "simulator-2", BUNDLE));
        assertThat(pool.servedBundles()).singleElement().satisfies(served -> {
            assertThat(served.digests()).isEqualTo(BUNDLE);
            assertThat(served.laneCount()).isEqualTo(2);
        });
    }

    @Test
    void theBundleDeclaresTheJarDigestsAsTheServiceImagesAndTheConfiguredRevisions() {
        var provenance = pool(1).servedBundles().getFirst().provenance();

        assertThat(provenance.gameService().imageDigest()).matches("sha256:[0-9a-f]{64}");
        assertThat(provenance.gameService().imageDigest())
                .isNotEqualTo(provenance.timelineService().imageDigest())
                .isNotEqualTo(provenance.readService().imageDigest());
        assertThat(provenance.gameService().sourceRevision()).isEqualTo("1111111");
        assertThat(provenance.timelineService().sourceRevision()).isEqualTo("2222222");
        assertThat(provenance.readService().sourceRevision()).isEqualTo("3333333");
        assertThat(provenance.contracts()).containsEntry("session-api", "3.0.0");
    }

    @Test
    void eachSlotHostsOneCaseAtATimeAndIsFreeAgainWhenItsLaneIsClosed() {
        var pool = pool(2);
        pool.prepare(List.of());
        scheduler.runAll();

        var first = pool.acquire(CASE, BUNDLE, null).orElseThrow();
        var second = pool.acquire(OTHER_CASE, BUNDLE, null).orElseThrow();

        assertThat(first.laneId()).isNotEqualTo(second.laneId());
        assertThat(pool.acquire(THIRD_CASE, BUNDLE, null)).isEmpty();

        first.close();

        assertThat(pool.acquire(THIRD_CASE, BUNDLE, null).orElseThrow().laneId())
                .isEqualTo(first.laneId());
    }

    @Test
    void aSlotWhoseServicesCannotStartFailsTheAttemptAndIsFreeAgainOnceReturned() {
        var pool = pool(1);
        pool.prepare(List.of());
        scheduler.runAll();
        var lane = pool.acquire(CASE, BUNDLE, null).orElseThrow();

        assertThatThrownBy(() -> lane.open(context(), null)).isInstanceOfSatisfying(AttemptFailedException.class, e -> {
            assertThat(e.failure().code()).isEqualTo(FailureCode.EXECUTION_FAILED);
            assertThat(e.getMessage()).contains("simulator-1").contains("could not start");
        });
        lane.close();
        scheduler.runAll();

        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    @Test
    void aSlotWhoseAttemptWasInterruptedIsHeldForThatCase() {
        var pool = pool(1);
        pool.prepare(List.of());
        scheduler.runAll();
        var lane = pool.acquire(CASE, BUNDLE, null).orElseThrow();
        var context = context();
        assertThatThrownBy(() -> lane.open(context, null)).isInstanceOf(AttemptFailedException.class);

        lane.retainForRecovery();
        lane.close();

        assertThat(pool.heldCases()).containsExactly(CASE);
        pool.releaseHeld(CASE);
        scheduler.runAll();
        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    @Test
    void aLaneClosedBeforeItWasOpenedIsFreeAtOnce() {
        var pool = pool(1);
        pool.prepare(List.of());
        scheduler.runAll();

        pool.acquire(CASE, BUNDLE, null).orElseThrow().close();

        assertThat(pool.freeLanes()).containsOnlyKeys("simulator-1");
    }

    @Test
    void aCaseOfAnotherBundleGetsNoSlot() {
        var pool = pool(1);
        pool.prepare(List.of());
        scheduler.runAll();

        assertThat(pool.acquire(CASE, new BundleDigests("e".repeat(64), "b".repeat(64)), null))
                .isEmpty();
    }

    @Test
    void aMissingJarFailsStartupNamingTheSlotAndThePath() throws IOException {
        Files.delete(timelineJar);

        assertThatThrownBy(() -> pool(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulator-1")
                .hasMessageContaining("timeline-service jar")
                .hasMessageContaining(timelineJar.toString());
    }

    @Test
    void aMissingRulesFileFailsStartupNamingTheSlotAndThePath() throws IOException {
        Files.delete(rules);

        assertThatThrownBy(() -> pool(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulator-1")
                .hasMessageContaining(rules.toString());
    }

    @Test
    void aRulesFileOfAnotherTypeFailsStartup() throws IOException {
        rules = Files.writeString(directory.resolve("rules.txt"), "game.rules.max-eras=3");

        assertThatThrownBy(() -> pool(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a .yml, .yaml or .properties file");
    }

    @Test
    void aJarThatIsNotExecutableFailsStartup() {
        readJar = FakeServiceJars.write(directory, "plain.jar", null, Map.of());

        assertThatThrownBy(() -> pool(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Start-Class");
    }

    private static CaseLane.CaseContext context() {
        return new CaseLane.CaseContext(
                CASE,
                new UUID(5, 5),
                new UUID(6, 6),
                "42",
                "f".repeat(64),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private SimulatorLanePool pool(int slots) {
        var declared = new SimulatorLanePool.Declared(
                new BundleProvenance.Artifact("c".repeat(64), null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                "1111111",
                "2222222",
                "3333333",
                new TreeMap<>(Map.of("session-api", "3.0.0")));
        return SimulatorLanePool.create(
                new SimulatorLanePool.Deployment(
                        slots, gameJar, timelineJar, readJar, rules, directory.resolve("work"), 6, BUNDLE, declared),
                new SimulatorLanePool.Collaborators(
                        null,
                        new InMemoryCommandLedger(),
                        new InMemoryEvidenceLedger(),
                        JsonMapper.builder().build(),
                        Clock.systemUTC(),
                        new LaneEndpoints.Barrier(Duration.ofMillis(1), 1, 1),
                        Sleeper.thread(),
                        new LaneWaits(new SimpleMeterRegistry()),
                        scheduler));
    }
}
