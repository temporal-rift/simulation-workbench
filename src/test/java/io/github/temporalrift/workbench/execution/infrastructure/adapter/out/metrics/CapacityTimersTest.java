package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneWaits;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.MeteredLaneResets;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.infrastructure.adapter.out.metrics.MicrometerDecisionTimings;

class CapacityTimersTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void anAttemptIsTimedByLanePlayersStateAndFailureCode() {
        var timings = new MicrometerAttemptTimings(registry);

        timings.ended("lane-1", 4, AttemptState.SUCCEEDED, null, Duration.ofSeconds(2));
        timings.ended("lane-1", 4, AttemptState.FAILED, FailureCode.RUNNER_TIMEOUT, Duration.ofSeconds(9));

        assertThat(registry.get("workbench.case.attempt")
                        .tags("lane", "lane-1", "players", "4", "state", "SUCCEEDED", "failure", "none")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("workbench.case.attempt")
                        .tags("lane", "lane-1", "players", "4", "state", "FAILED", "failure", "RUNNER_TIMEOUT")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void everyDecisionIsTimedUnderItsPolicyId() {
        var timings = new MicrometerDecisionTimings(registry);

        for (var decision = 0; decision < 40; decision++) {
            timings.time("faction-greedy-v1", PolicyDecision.Exhausted::new);
        }

        assertThat(registry.get("workbench.policy.decision")
                        .tag("policy", "faction-greedy-v1")
                        .timer()
                        .count())
                .isEqualTo(40);
    }

    @Test
    void noTimerIsTaggedBeyondItsDocumentedKeys() {
        new MicrometerAttemptTimings(registry).ended("lane-1", 3, AttemptState.CANCELLED, null, Duration.ofSeconds(1));
        new MicrometerDecisionTimings(registry).time("random-v1", PolicyDecision.Exhausted::new);
        var waits = new LaneWaits(registry);
        for (var kind : LaneWaits.Kind.values()) {
            waits.waited(kind, 1);
        }
        new MeteredLaneResets((_, _, _) -> {}, registry).reset("lane-1", UUID.randomUUID(), UUID.randomUUID());

        Map<String, List<String>> keysByTimer = registry.getMeters().stream()
                .collect(Collectors.toMap(
                        meter -> meter.getId().getName(),
                        meter -> meter.getId().getTags().stream()
                                .map(Tag::getKey)
                                .sorted()
                                .toList(),
                        (first, _) -> first));

        assertThat(keysByTimer)
                .containsOnly(
                        Map.entry("workbench.case.attempt", List.of("failure", "lane", "players", "state")),
                        Map.entry("workbench.policy.decision", List.of("policy")),
                        Map.entry("workbench.lane.wait", List.of("kind")),
                        Map.entry("workbench.lane.reset", List.of("lane", "outcome")));
        assertThat(registry.getMeters().stream().map(Meter::getId).flatMap(id -> id.getTags().stream()))
                .noneMatch(tag -> tag.getValue().matches("(?i).*[0-9a-f]{8}-[0-9a-f]{4}-.*"));
    }
}
