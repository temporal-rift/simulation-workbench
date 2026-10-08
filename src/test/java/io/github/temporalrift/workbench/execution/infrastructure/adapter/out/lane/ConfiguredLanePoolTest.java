package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.StubEventObserver;

class ConfiguredLanePoolTest {

    private final ConfiguredLanePool pool = new ConfiguredLanePool(
            List.of(lane("a"), lane("b")),
            new RestApiClients(Duration.ofSeconds(1), Duration.ofSeconds(1)),
            new InMemoryCommandLedger(),
            new InMemoryEvidenceLedger(),
            (_, _, _, _) -> new StubEventObserver(),
            Clock.systemUTC(),
            new LaneEndpoints.Barrier(Duration.ZERO, 1, 1),
            _ -> {});

    @Test
    void eachLaneHostsOneCaseAtATime() {
        var first = pool.acquire(null).orElseThrow();
        var second = pool.acquire(null).orElseThrow();

        assertThat(first.laneId()).isNotEqualTo(second.laneId());
        assertThat(pool.hasFreeLane()).isFalse();
        assertThat(pool.acquire(null)).isEmpty();

        first.close();

        assertThat(pool.hasFreeLane()).isTrue();
        assertThat(pool.acquire(null).orElseThrow().laneId()).isEqualTo(first.laneId());
    }

    @Test
    void aRecoveringAttemptGetsItsPreviousLaneBackWhenItIsFree() {
        var preferred = pool.acquire("b").orElseThrow();

        assertThat(preferred.laneId()).isEqualTo("b");
        assertThat(pool.acquire("b").orElseThrow().laneId()).isEqualTo("a");
    }

    @Test
    void aLaneWithNoFreeCapacityIsNeverOverCommitted() {
        pool.acquire(null).orElseThrow();
        pool.acquire(null).orElseThrow();

        assertThat(pool.acquire("a")).isEmpty();
    }

    @Test
    void aPoolWithoutLanesHasNothingToOffer() {
        var empty = new ConfiguredLanePool(
                List.of(),
                new RestApiClients(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new InMemoryCommandLedger(),
                new InMemoryEvidenceLedger(),
                (_, _, _, _) -> new StubEventObserver(),
                Clock.systemUTC(),
                new LaneEndpoints.Barrier(Duration.ZERO, 1, 1),
                _ -> {});

        assertThat(empty.hasFreeLane()).isFalse();
        assertThat(empty.acquire(null)).isEmpty();
    }

    private static LaneEndpoints lane(String id) {
        return new LaneEndpoints(
                id,
                "http://game",
                "http://timeline",
                "http://read",
                "operator",
                "game.events",
                "timeline.events",
                List.of());
    }
}
