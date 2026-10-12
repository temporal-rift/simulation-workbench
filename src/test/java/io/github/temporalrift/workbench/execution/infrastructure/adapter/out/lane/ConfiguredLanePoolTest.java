package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.StubEventObserver;

class ConfiguredLanePoolTest {

    private static final BundleDigests BASELINE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final BundleDigests CANDIDATE = new BundleDigests("f".repeat(64), "b".repeat(64));

    private final ConfiguredLanePool pool =
            poolOf(List.of(lane("a", BASELINE), lane("b", BASELINE), lane("c", CANDIDATE)));

    @Test
    void eachLaneHostsOneCaseAtATime() {
        var first = pool.acquire(BASELINE, null).orElseThrow();
        var second = pool.acquire(BASELINE, null).orElseThrow();

        assertThat(first.laneId()).isNotEqualTo(second.laneId());
        assertThat(pool.freeLanes()).containsOnlyKeys("c");
        assertThat(pool.acquire(BASELINE, null)).isEmpty();

        first.close();

        assertThat(pool.acquire(BASELINE, null).orElseThrow().laneId()).isEqualTo(first.laneId());
    }

    @Test
    void aCaseGetsOnlyALaneServingItsBundle() {
        assertThat(pool.acquire(CANDIDATE, "a").orElseThrow().laneId()).isEqualTo("c");
        assertThat(pool.acquire(CANDIDATE, null)).isEmpty();
        assertThat(pool.acquire(new BundleDigests("e".repeat(64), "b".repeat(64)), null))
                .isEmpty();
    }

    @Test
    void aRecoveringAttemptGetsItsPreviousLaneBackWhenItIsFree() {
        var preferred = pool.acquire(BASELINE, "b").orElseThrow();

        assertThat(preferred.laneId()).isEqualTo("b");
        assertThat(pool.acquire(BASELINE, "b").orElseThrow().laneId()).isEqualTo("a");
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
    void servedBundlesIncludeBusyLanes() {
        pool.acquire(CANDIDATE, null).orElseThrow();

        assertThat(pool.servedBundles()).containsExactlyInAnyOrder(BASELINE, CANDIDATE);
    }

    @Test
    void aPoolWithoutLanesHasNothingToOffer() {
        var empty = poolOf(List.of());

        assertThat(empty.freeLanes()).isEmpty();
        assertThat(empty.servedBundles()).isEmpty();
        assertThat(empty.acquire(BASELINE, null)).isEmpty();
    }

    private static ConfiguredLanePool poolOf(List<LaneEndpoints> lanes) {
        return new ConfiguredLanePool(
                lanes,
                new RestApiClients(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                services(),
                Clock.systemUTC(),
                new LaneEndpoints.Barrier(Duration.ZERO, 1, 1),
                _ -> {});
    }

    private static LaneServices services() {
        return new LaneServices(
                new InMemoryCommandLedger(), new InMemoryEvidenceLedger(), (_, _, _, _, _) -> new StubEventObserver());
    }

    private static LaneEndpoints lane(String id, BundleDigests bundle) {
        return new LaneEndpoints(
                id,
                "http://game",
                "http://timeline",
                "http://read",
                "operator",
                bundle,
                "game.events",
                "timeline.events",
                null,
                List.of());
    }
}
