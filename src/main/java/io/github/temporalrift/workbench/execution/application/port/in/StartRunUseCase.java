package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

/** Creates a queued run over a frozen experiment, once per idempotency key. */
public interface StartRunUseCase {

    RunView handle(Command command);

    /**
     * @param concurrency how many cases may execute at once from the start, or null for the experiment's own bound
     */
    record Command(UUID experimentId, UUID idempotencyKey, Integer concurrency) {

        public Command(UUID experimentId, UUID idempotencyKey) {
            this(experimentId, idempotencyKey, null);
        }
    }
}
