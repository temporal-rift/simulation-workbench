package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.github.temporalrift.workbench.execution.LaneService;

/** Publishes {@code workbench.lane.context.close}: the duration of closing one case's context, by lane and outcome. */
public class MeteredLaneContexts implements LaneContexts {

    private enum Outcome {
        CLEAN,
        FAILED,
        TIMEOUT
    }

    private final LaneContexts delegate;
    private final MeterRegistry registry;

    public MeteredLaneContexts(LaneContexts delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public void close(String laneId, UUID caseKey, Set<LaneService> services) {
        var sample = Timer.start(registry);
        var outcome = Outcome.FAILED;
        try {
            delegate.close(laneId, caseKey, services);
            outcome = Outcome.CLEAN;
        } catch (CloseTimeoutException e) {
            outcome = Outcome.TIMEOUT;
            throw e;
        } finally {
            sample.stop(registry.timer("workbench.lane.context.close", "lane", laneId, "outcome", outcome.name()));
        }
    }

    @Override
    public void closeAnyOpen(String laneId) {
        delegate.closeAnyOpen(laneId);
    }
}
