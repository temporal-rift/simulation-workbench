package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

/** Resumes an interrupted run without repeating any completed case. */
public interface ResumeRunUseCase {

    RunView handle(Command command);

    record Command(UUID runId, UUID idempotencyKey) {}
}
