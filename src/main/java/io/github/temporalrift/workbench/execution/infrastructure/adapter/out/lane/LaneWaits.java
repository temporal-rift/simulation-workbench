package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;

/** Publishes {@code workbench.lane.wait}: the time spent waiting for the services after a command, by kind. */
public class LaneWaits {

    /** What a command waited for. */
    public enum Kind {
        /** The service answering the command. */
        ACKNOWLEDGEMENT,
        /** Both services reporting their work drained. */
        CAUSAL_BARRIER,
        /** The projection and the event observer catching up. */
        PROJECTION_BARRIER
    }

    private final MeterRegistry registry;

    public LaneWaits(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A reading of the registry's monotonic clock, in nanoseconds. */
    public long now() {
        return registry.config().clock().monotonicTime();
    }

    public void record(Kind kind, long nanos) {
        registry.timer("workbench.lane.wait", "kind", kind.name()).record(nanos, TimeUnit.NANOSECONDS);
    }
}
