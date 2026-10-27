package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

/**
 * One set of the three services hosted in this JVM. A slot plays one case at a time and holds the running services
 * only while a case needs them: the services keep their state in memory, so stopping them is what makes the slot
 * clean for the next case.
 */
final class SimulatorSlot {

    private final String id;
    private final SlotRuntime.Build build;
    private final List<UUID> botPlayerIds;
    private final Clock clock;
    private SlotRuntime runtime;

    SimulatorSlot(String id, SlotRuntime.Build build, List<UUID> botPlayerIds, Clock clock) {
        this.id = id;
        this.build = build;
        this.botPlayerIds = botPlayerIds;
        this.clock = clock;
    }

    String id() {
        return id;
    }

    /** Services started afresh for a case, whatever an earlier case left in them. */
    synchronized SlotRuntime runtime() {
        stop();
        try {
            runtime = SlotRuntime.start(build, botPlayerIds, clock);
        } catch (RuntimeException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "Simulator slot " + id + " could not start its services: "
                            + NestedExceptionUtils.getMostSpecificCause(e).getMessage(),
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
