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
     * Resumes an interrupted run, which makes no progress until it is.
     *
     * @throws ExecutionRefusedException when the run is not interrupted or does not exist
     */
    void resume(UUID runId);
}
