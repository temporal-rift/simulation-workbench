package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs lane resets off the worker threads; replaced in tests so retries never wait on a wall clock. */
@FunctionalInterface
public interface ResetScheduler {

    void schedule(Runnable task, Duration delay);

    static ResetScheduler on(ScheduledExecutorService executor) {
        return (task, delay) -> executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    }
}
