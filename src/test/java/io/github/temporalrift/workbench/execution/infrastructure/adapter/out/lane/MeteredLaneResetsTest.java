package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MeteredLaneResetsTest {

    private static final UUID KEY = new UUID(1, 1);

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);

    @Test
    void aCleanResetIsTimedForItsWholeDuration() {
        var resets = new MeteredLaneResets(taking(Duration.ofSeconds(3), null), registry);

        resets.reset("lane-1", KEY, KEY);

        var timer = registry.get("workbench.lane.reset")
                .tags("lane", "lane-1", "outcome", "CLEAN")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void aResetTheHookRefusedIsTimedAsFailed() {
        var resets =
                new MeteredLaneResets(taking(Duration.ofSeconds(1), new IllegalStateException("status 500")), registry);

        assertThatThrownBy(() -> resets.reset("lane-1", KEY, KEY)).hasMessage("status 500");

        assertThat(registry.get("workbench.lane.reset")
                        .tags("lane", "lane-1", "outcome", "FAILED")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aResetSlowerThanTheResetTimeoutIsTimedAsTimedOut() {
        var resets = new MeteredLaneResets(
                taking(Duration.ofSeconds(30), new ResetTimeoutException("lane-1", new RuntimeException())), registry);

        assertThatThrownBy(() -> resets.reset("lane-1", KEY, KEY)).isInstanceOf(ResetTimeoutException.class);

        assertThat(registry.get("workbench.lane.reset")
                        .tags("lane", "lane-1", "outcome", "TIMEOUT")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    /** A hook that answers after the given time on the registry's clock, failing with {@code failure} if any. */
    private LaneResets taking(Duration time, RuntimeException failure) {
        return (_, _, _) -> {
            clock.add(time);
            if (failure != null) {
                throw failure;
            }
        };
    }
}
