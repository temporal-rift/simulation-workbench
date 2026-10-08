package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

/** Stops scheduling new cases of a run while keeping every completed result. */
public interface CancelRunUseCase {

    Result handle(Command command);

    record Command(UUID runId, UUID idempotencyKey) {}

    /** {@code accepted} is false when the run had already reached a terminal state. */
    record Result(RunView run, boolean accepted) {}
}
