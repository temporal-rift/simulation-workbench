package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ResetScheduler;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

/**
 * One set of the three services hosted in this JVM. A slot plays one case at a time on services it keeps across
 * cases, as a real lane does: each case's execution context is closed on them before the next opens, which leaves them
 * as clean as newly started ones. The services are started for the first case and restarted only to recover the
 * slot, after a close failed or a service still held an earlier case. A slot that hosted no case for its idle timeout
 * stops them, so a workbench with no run in progress gives their memory back.
 */
final class SimulatorSlot {

    private final String id;
    private final SlotRuntime.Build build;
    private final List<UUID> botPlayerIds;
    private final Clock clock;
    private final Duration idleTimeout;
    private final ResetScheduler scheduler;
    private SlotRuntime runtime;
    private boolean hosting;
    private long tenancies;

    /** @param idleTimeout how long the services may host no case before they stop; zero keeps them running */
    SimulatorSlot(
            String id,
            SlotRuntime.Build build,
            List<UUID> botPlayerIds,
            Clock clock,
            Duration idleTimeout,
            ResetScheduler scheduler) {
        this.id = id;
        this.build = build;
        this.botPlayerIds = botPlayerIds;
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.scheduler = scheduler;
    }

    String id() {
        return id;
    }

    /** The running services for the case about to be hosted, started when the slot holds none. */
    synchronized SlotRuntime runtime() {
        hosting = true;
        tenancies++;
        if (runtime == null) {
            runtime = start();
        }
        return runtime;
    }

    /** The services while they run, without starting them. */
    synchronized Optional<SlotRuntime> running() {
        return Optional.ofNullable(runtime);
    }

    /**
     * The hosted case is over; the slot stops its services once it stays idle long enough. Called before the lane is
     * given back, so no later case can be mistaken for idleness.
     */
    synchronized void released() {
        hosting = false;
        scheduleIdleStop();
    }

    /**
     * Stops and starts the services, so the slot is clean whatever the previous ones held. A recovery can outlast the
     * idle timeout, so restarted services that host no case get an idle stop of their own.
     */
    synchronized void restart() {
        stop();
        runtime = start();
        if (!hosting) {
            scheduleIdleStop();
        }
    }

    /** Stops the services and drops everything they held. */
    synchronized void stop() {
        if (runtime != null) {
            var stopping = runtime;
            runtime = null;
            stopping.close();
        }
    }

    private void scheduleIdleStop() {
        if (idleTimeout.isZero() || runtime == null) {
            return;
        }
        var released = tenancies;
        scheduler.schedule(() -> stopIfIdleSince(released), idleTimeout);
    }

    private synchronized void stopIfIdleSince(long released) {
        if (!hosting && tenancies == released) {
            stop();
        }
    }

    private SlotRuntime start() {
        try {
            return SlotRuntime.start(build, botPlayerIds, clock);
        } catch (RuntimeException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "Simulator slot " + id + " could not start its services: "
                            + NestedExceptionUtils.getMostSpecificCause(e).getMessage(),
                    e);
        }
    }
}
