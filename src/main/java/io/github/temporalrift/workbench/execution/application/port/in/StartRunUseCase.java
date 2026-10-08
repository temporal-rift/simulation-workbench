package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

/** Creates a queued run over a frozen experiment, once per idempotency key. */
public interface StartRunUseCase {

    RunView handle(Command command);

    record Command(UUID experimentId, UUID idempotencyKey) {}
}
