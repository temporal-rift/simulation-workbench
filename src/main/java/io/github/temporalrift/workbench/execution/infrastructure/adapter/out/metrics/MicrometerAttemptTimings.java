package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.metrics;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.temporalrift.workbench.execution.domain.port.out.AttemptTimings;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;

/** Publishes {@code workbench.case.attempt}, tagged by lane, player count, final state and failure code. */
public class MicrometerAttemptTimings implements AttemptTimings {

    private final MeterRegistry registry;

    public MicrometerAttemptTimings(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void ended(String laneId, int players, AttemptState state, FailureCode failure, Duration elapsed) {
        registry.timer(
                        "workbench.case.attempt",
                        "lane",
                        laneId,
                        "players",
                        Integer.toString(players),
                        "state",
                        state.name(),
                        "failure",
                        failure == null ? "none" : failure.name())
                .record(elapsed);
    }
}
