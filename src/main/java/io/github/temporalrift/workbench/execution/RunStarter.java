package io.github.temporalrift.workbench.execution;

import java.util.UUID;

/** Starts runs of frozen experiments for other modules. */
public interface RunStarter {

    /**
     * Creates a queued run over the frozen experiment, once per idempotency key, and returns its id.
     *
     * @throws ExecutionRefusedException when some variant has no configured lane that fits it
     */
    UUID start(UUID experimentId, UUID idempotencyKey);

    /**
     * Creates a queued run like {@link #start}, but with no case allowed to execute until {@link #setConcurrency}
     * raises the bound, so it cannot play a case before its caller says so.
     *
     * @throws ExecutionRefusedException when some variant has no configured lane that fits it
     */
    UUID startHeld(UUID experimentId, UUID idempotencyKey);

    /**
     * Resumes an interrupted run, which makes no progress until it is.
     *
     * @throws ExecutionRefusedException when the run is not interrupted or does not exist
     */
    void resume(UUID runId);

    /**
     * Sets how many of the run's cases may execute at once. The bound applies to the next case claimed: cases already
     * executing finish, and 0 holds back every case that has not started.
     *
     * @throws ExecutionRefusedException when the run does not exist, is finished or the bound is negative
     */
    void setConcurrency(UUID runId, int concurrency);

    /**
     * Stops scheduling new cases of the run, keeping every completed result; cancelling a finished run is a no-op.
     *
     * @throws ExecutionRefusedException when the run does not exist
     */
    void cancel(UUID runId);
}
