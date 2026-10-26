package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.ManualResetScheduler;
import io.github.temporalrift.workbench.execution.support.Provenances;
import io.github.temporalrift.workbench.execution.support.RecordingContexts;
import io.github.temporalrift.workbench.execution.support.RecordingResets;
import io.github.temporalrift.workbench.execution.support.StubEventObserver;

class ConfiguredLanePoolTest {

    private static final BundleDigests BASELINE = new BundleDigests("a".repeat(64), "b".repeat(64));
    /** The same content under other rules: a lane serving it fits the same cases. */
    private static final BundleDigests TUNED = new BundleDigests("f".repeat(64), "b".repeat(64));
    /** Other content: a lane serving it fits other cases. */
    private static final BundleDigests OTHER_CONTENT = new BundleDigests("a".repeat(64), "e".repeat(64));

    private static final LaneFit FIT = Provenances.fitOf(BASELINE);
    private static final LaneFit OTHER_FIT = Provenances.fitOf(OTHER_CONTENT);

    private final RecordingContexts contexts = new RecordingContexts();
    private final RecordingResets resets = new RecordingResets();
    private final ManualResetScheduler scheduler = new ManualResetScheduler();
    private final ConfiguredLanePool pool = prepared(
            poolOf(List.of(lane("a", BASELINE, false), lane("b", TUNED, false), lane("c", OTHER_CONTENT, false))));

    @Test
    void eachLaneHostsOneCaseAtATime() {
        var first = pool.acquire(FIT, null).orElseThrow();
        var second = pool.acquire(FIT, null).orElseThrow();

        assertThat(first.laneId()).isNotEqualTo(second.laneId());
        assertThat(pool.freeLanes()).containsOnlyKeys("c");
        assertThat(pool.acquire(FIT, null)).isEmpty();

        first.close();

        assertThat(pool.acquire(FIT, null).orElseThrow().laneId()).isEqualTo(first.laneId());
    }

    @Test
    void aCaseGetsAnyLaneThatFitsItWhateverRulesTheLaneServes() {
        assertThat(pool.acquire(FIT, "b").orElseThrow().laneId()).isEqualTo("b");
        assertThat(pool.acquire(FIT, null).orElseThrow().laneId()).isEqualTo("a");
    }

    @Test
    void aCaseGetsOnlyALaneThatFitsIt() {
        assertThat(pool.acquire(OTHER_FIT, "a").orElseThrow().laneId()).isEqualTo("c");
        assertThat(pool.acquire(OTHER_FIT, null)).isEmpty();
        assertThat(pool.acquire(Provenances.fitOf(new BundleDigests("a".repeat(64), "d".repeat(64))), null))
                .isEmpty();
    }

    @Test
    void aRecoveringAttemptGetsItsPreviousLaneBackWhenItIsFree() {
        var preferred = pool.acquire(FIT, "b").orElseThrow();

        assertThat(preferred.laneId()).isEqualTo("b");
        assertThat(pool.acquire(FIT, "b").orElseThrow().laneId()).isEqualTo("a");
    }

    @Test
    void aReproductionAcquiresExactlyTheLaneThatPlayedTheCaseWhileItFitsTheCase() {
        assertThat(pool.freeLanes()).isEqualTo(Map.of("a", FIT, "b", FIT, "c", OTHER_FIT));
        assertThat(pool.acquireExactly("b", OTHER_FIT)).isEmpty();
        var exact = pool.acquireExactly("b", FIT).orElseThrow();

        assertThat(exact.laneId()).isEqualTo("b");
        assertThat(pool.acquireExactly("b", FIT)).isEmpty();
        assertThat(pool.freeLanes()).containsOnlyKeys("a", "c");
        assertThat(pool.acquireExactly("unknown", FIT)).isEmpty();

        exact.close();
        assertThat(pool.freeLanes()).containsOnlyKeys("a", "b", "c");
    }

    @Test
    void servedBundlesCountEveryLaneServingThemBusyOrFreeInDigestOrder() {
        pool.acquire(OTHER_FIT, null).orElseThrow();

        assertThat(pool.servedBundles())
                .containsExactly(
                        new ServedBundle(BASELINE, Provenances.of(BASELINE), 1),
                        new ServedBundle(OTHER_CONTENT, Provenances.of(OTHER_CONTENT), 1),
                        new ServedBundle(TUNED, Provenances.of(TUNED), 1));
    }

    @Test
    void aPoolWithoutLanesHasNothingToOffer() {
        var empty = prepared(poolOf(List.of()));

        assertThat(empty.freeLanes()).isEmpty();
        assertThat(empty.servedBundles()).isEmpty();
        assertThat(empty.acquire(FIT, null)).isEmpty();
    }

    @Test
    void aLaneIsOfferedOnlyOncePrepared() {
        var unprepared = poolOf(List.of(lane("a", BASELINE, true), lane("b", BASELINE, false)));

        assertThat(unprepared.freeLanes()).isEmpty();
        unprepared.prepare();

        assertThat(unprepared.freeLanes()).containsOnlyKeys("a", "b");
        assertThat(contexts.calls()).extracting(RecordingContexts.Call::laneId).containsExactlyInAnyOrder("a", "b");
        assertThat(resets.calls()).isEmpty();
    }

    @Test
    void aLaneThatHostedNoContextIsOfferedAgainWithoutAClose() {
        var resettable = prepared(poolOf(List.of(lane("a", BASELINE, true))));

        resettable.acquire(FIT, null).orElseThrow().close();

        assertThat(resettable.freeLanes()).containsOnlyKeys("a");
        assertThat(contexts.calls()).isEmpty();
        assertThat(resets.calls()).isEmpty();
    }

    private ConfiguredLanePool prepared(ConfiguredLanePool unprepared) {
        unprepared.prepare();
        scheduler.runAll();
        contexts.clear();
        resets.clear();
        return unprepared;
    }

    private ConfiguredLanePool poolOf(List<LaneEndpoints> lanes) {
        return new ConfiguredLanePool(
                lanes,
                new LaneRoster(lanes.stream().map(LaneEndpoints::member).toList(), contexts, resets, scheduler),
                new RestApiClients(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                services(),
                Clock.systemUTC(),
                new HttpGameSession.Pacing(
                        new LaneEndpoints.Barrier(Duration.ZERO, Duration.ofSeconds(1)),
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
                Provenances.of(bundle),
                "game.events",
                "timeline.events",
                null,
                resettable ? URI.create("http://recycler/lanes/" + id + "/reset") : null,
                List.of());
    }
}
