package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Duration;

import io.github.temporalrift.workbench.execution.domain.run.LeaseLostException;

/** Pauses between barrier checks; replaced in tests so they never wait on a wall clock. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    /** Sleeps the thread. An interrupt means the worker is shutting down, so the attempt is abandoned. */
    static Sleeper thread() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LeaseLostException();
            }
        };
    }
}
