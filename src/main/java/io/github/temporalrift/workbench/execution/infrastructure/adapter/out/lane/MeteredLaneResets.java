package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Publishes {@code workbench.lane.reset}: the duration of every reset-hook call, by lane and outcome. */
public class MeteredLaneResets implements LaneResets {

    private enum Outcome {
        CLEAN,
        FAILED,
        TIMEOUT
    }

    private final LaneResets delegate;
    private final MeterRegistry registry;

    public MeteredLaneResets(LaneResets delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public void reset(String laneId, UUID caseKey, UUID idempotencyKey) {
        var sample = Timer.start(registry);
        var outcome = Outcome.FAILED;
        try {
            delegate.reset(laneId, caseKey, idempotencyKey);
            outcome = Outcome.CLEAN;
        } catch (ResetTimeoutException e) {
            outcome = Outcome.TIMEOUT;
            throw e;
        } finally {
            sample.stop(registry.timer("workbench.lane.reset", "lane", laneId, "outcome", outcome.name()));
        }
    }
}
