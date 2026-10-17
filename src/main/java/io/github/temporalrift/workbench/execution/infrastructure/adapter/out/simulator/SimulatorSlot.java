package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.time.Clock;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;

/**
 * One set of the three services hosted in this JVM. A slot plays one case at a time and holds the running services
 * only while a case needs them: the services keep their state in memory, so stopping them is what makes the slot
 * clean for the next case.
 */
final class SimulatorSlot {

    private final String id;
    private final SlotRuntime.Build build;
    private final int bots;
    private final Clock clock;
    private SlotRuntime runtime;

    SimulatorSlot(String id, SlotRuntime.Build build, int bots, Clock clock) {
        this.id = id;
        this.build = build;
        this.bots = bots;
        this.clock = clock;
    }

    String id() {
        return id;
    }

    /**
     * The running services for a case. A case that resumes its own game keeps the services that hold it; every other
     * case gets services started afresh, whatever an earlier case left in them.
     */
    synchronized SlotRuntime runtime(boolean resume) {
        if (runtime != null && resume) {
            return runtime;
        }
        stop();
        try {
            runtime = SlotRuntime.start(build, bots, clock);
        } catch (RuntimeException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "Simulator slot " + id + " could not start its services: " + e.getMessage(),
                    e);
        }
        return runtime;
    }

    /** Stops the services and drops everything they held. */
    synchronized void stop() {
        if (runtime != null) {
            var stopping = runtime;
            runtime = null;
            stopping.close();
        }
    }
}
