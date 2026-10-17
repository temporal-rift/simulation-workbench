package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.ManualResetScheduler;
import io.github.temporalrift.workbench.execution.support.Provenances;
import io.github.temporalrift.workbench.execution.support.RecordingResets;
import io.github.temporalrift.workbench.execution.support.StubEventObserver;

class ConfiguredLanePoolTest {

    private static final BundleDigests BASELINE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final BundleDigests CANDIDATE = new BundleDigests("f".repeat(64), "b".repeat(64));
    private static final UUID CASE = new UUID(1, 1);
    private static final UUID CASE_KEY = new UUID(2, 2);
    private static final UUID OTHER_CASE = new UUID(3, 3);

    private final RecordingResets resets = new RecordingResets();
    private final ManualResetScheduler scheduler = new ManualResetScheduler();
    private final ConfiguredLanePool pool =
            poolOf(List.of(lane("a", BASELINE, false), lane("b", BASELINE, false), lane("c", CANDIDATE, false)));

    @Test
    void eachLaneHostsOneCaseAtATime() {
        var first = pool.acquire(CASE, BASELINE, null).orElseThrow();
        var second = pool.acquire(OTHER_CASE, BASELINE, null).orElseThrow();

        assertThat(first.laneId()).isNotEqualTo(second.laneId());
        assertThat(pool.freeLanes()).containsOnlyKeys("c");
        assertThat(pool.acquire(CASE, BASELINE, null)).isEmpty();

        first.close();

        assertThat(pool.acquire(CASE, BASELINE, null).orElseThrow().laneId()).isEqualTo(first.laneId());
    }

    @Test
    void aCaseGetsOnlyALaneServingItsBundle() {
        assertThat(pool.acquire(CASE, CANDIDATE, "a").orElseThrow().laneId()).isEqualTo("c");
        assertThat(pool.acquire(CASE, CANDIDATE, null)).isEmpty();
        assertThat(pool.acquire(CASE, new BundleDigests("e".repeat(64), "b".repeat(64)), null))
                .isEmpty();
    }

    @Test
    void aRecoveringAttemptGetsItsPreviousLaneBackWhenItIsFree() {
        var preferred = pool.acquire(CASE, BASELINE, "b").orElseThrow();

        assertThat(preferred.laneId()).isEqualTo("b");
        assertThat(pool.acquire(OTHER_CASE, BASELINE, "b").orElseThrow().laneId())
                .isEqualTo("a");
    }

    @Test
    void aReproductionAcquiresExactlyTheLaneThatPlayedTheCaseWhileItServesTheBundle() {
        assertThat(pool.freeLanes()).isEqualTo(Map.of("a", BASELINE, "b", BASELINE, "c", CANDIDATE));
        assertThat(pool.acquireExactly("b", CANDIDATE)).isEmpty();
        var exact = pool.acquireExactly("b", BASELINE).orElseThrow();

        assertThat(exact.laneId()).isEqualTo("b");
        assertThat(pool.acquireExactly("b", BASELINE)).isEmpty();
        assertThat(pool.freeLanes()).containsOnlyKeys("a", "c");
        assertThat(pool.acquireExactly("unknown", BASELINE)).isEmpty();

        exact.close();
        assertThat(pool.freeLanes()).containsOnlyKeys("a", "b", "c");
    }

    @Test
    void servedBundlesCountEveryLaneServingThemBusyOrFreeInDigestOrder() {
        pool.acquire(CASE, CANDIDATE, null).orElseThrow();

        assertThat(pool.servedBundles())
                .containsExactly(
                        new ServedBundle(BASELINE, Provenances.sample(), 2),
                        new ServedBundle(CANDIDATE, Provenances.sample(), 1));
    }

    @Test
    void aPoolWithoutLanesHasNothingToOffer() {
        var empty = poolOf(List.of());

        assertThat(empty.freeLanes()).isEmpty();
        assertThat(empty.servedBundles()).isEmpty();
        assertThat(empty.acquire(CASE, BASELINE, null)).isEmpty();
    }

    @Test
    void aResettableLaneIsOfferedOnlyOnceItsFirstResetSucceeded() {
        var resettable = poolOf(List.of(lane("a", BASELINE, true), lane("b", BASELINE, false)));

        assertThat(resettable.freeLanes()).containsOnlyKeys("b");
        resettable.prepare(List.of());
        assertThat(resettable.freeLanes()).containsOnlyKeys("b");
        scheduler.runAll();

        assertThat(resettable.freeLanes()).containsOnlyKeys("a", "b");
        assertThat(resets.calls()).extracting(RecordingResets.Call::laneId).containsExactly("a");
    }

    @Test
    void aLaneThatHostedNoCaseIsOfferedAgainWithoutAReset() {
        var resettable = poolOf(List.of(lane("a", BASELINE, true)));
        resettable.prepare(List.of());
        scheduler.runAll();

        resettable.acquire(CASE, BASELINE, null).orElseThrow().close();

        assertThat(resettable.freeLanes()).containsOnlyKeys("a");
        assertThat(resets.calls()).hasSize(1);
    }

    @Test
    void aLaneHeldForAnInterruptedCaseIsOfferedToThatCaseAlone() {
        var resettable = poolOf(List.of(lane("a", BASELINE, true)));
        resettable.prepare(List.of(new LaneProvider.Hold("a", CASE, CASE_KEY)));

        assertThat(resettable.heldCases()).containsExactly(CASE);
        assertThat(resettable.acquire(OTHER_CASE, BASELINE, null)).isEmpty();
        assertThat(resettable.acquire(CASE, BASELINE, "a").orElseThrow().laneId())
                .isEqualTo("a");
        assertThat(resets.calls()).isEmpty();
    }

    private ConfiguredLanePool poolOf(List<LaneEndpoints> lanes) {
        return new ConfiguredLanePool(
                lanes,
                new LaneRoster(lanes.stream().map(LaneEndpoints::member).toList(), resets, scheduler),
                new RestApiClients(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                services(),
                Clock.systemUTC(),
                new HttpGameSession.Pacing(
                        new LaneEndpoints.Barrier(Duration.ZERO, 1, 1),
                        _ -> {},
                        new LaneWaits(new SimpleMeterRegistry())));
    }

    private static LaneServices services() {
        return new LaneServices(
                new InMemoryCommandLedger(), new InMemoryEvidenceLedger(), (_, _, _, _, _) -> new StubEventObserver());
    }

    private static LaneEndpoints lane(String id, BundleDigests bundle, boolean resettable) {
        return new LaneEndpoints(
                id,
                "http://game",
                "http://timeline",
                "http://read",
                "operator",
                bundle,
                Provenances.sample(),
                "game.events",
                "timeline.events",
                null,
                resettable ? URI.create("http://recycler/lanes/" + id + "/reset") : null,
                List.of());
    }
}
