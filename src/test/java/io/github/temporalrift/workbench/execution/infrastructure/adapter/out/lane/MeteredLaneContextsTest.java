package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.LaneService;

class MeteredLaneContextsTest {

    private static final UUID KEY = new UUID(1, 1);
    private static final Set<LaneService> BOTH = Set.of(LaneService.GAME, LaneService.TIMELINE);

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);

    @Test
    void aCleanCloseIsTimedForItsWholeDuration() {
        var contexts = new MeteredLaneContexts(taking(Duration.ofMillis(40), null), registry);

        contexts.close("lane-1", KEY, BOTH);

        var timer = registry.get("workbench.lane.context.close")
                .tags("lane", "lane-1", "outcome", "CLEAN")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(40.0);
    }

    @Test
    void aCloseAServiceRefusedIsTimedAsFailed() {
        var contexts = new MeteredLaneContexts(
                taking(Duration.ofMillis(5), new IllegalStateException("game-service answered the close with 500")),
                registry);

        assertThatThrownBy(() -> contexts.close("lane-1", KEY, BOTH)).hasMessageContaining("500");

        assertThat(registry.get("workbench.lane.context.close")
                        .tags("lane", "lane-1", "outcome", "FAILED")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aCloseSlowerThanTheTimeoutIsTimedAsTimedOut() {
        var contexts = new MeteredLaneContexts(
                taking(Duration.ofSeconds(30), new CloseTimeoutException("lane-1", new RuntimeException())), registry);

        assertThatThrownBy(() -> contexts.close("lane-1", KEY, BOTH)).isInstanceOf(CloseTimeoutException.class);

        assertThat(registry.get("workbench.lane.context.close")
                        .tags("lane", "lane-1", "outcome", "TIMEOUT")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void closingWhateverIsOpenIsNotTimedAsACase() {
        new MeteredLaneContexts(taking(Duration.ZERO, null), registry).closeAnyOpen("lane-1");

        assertThat(registry.find("workbench.lane.context.close").timers()).isEmpty();
    }

    /** Contexts that answer after the given time on the registry's clock, failing with {@code failure} if any. */
    private LaneContexts taking(Duration time, RuntimeException failure) {
        return new LaneContexts() {
            @Override
            public void close(String laneId, UUID caseKey, Set<LaneService> services) {
                clock.add(time);
                if (failure != null) {
                    throw failure;
                }
            }

            @Override
            public void closeAnyOpen(String laneId) {
                clock.add(time);
            }
        };
    }
}
