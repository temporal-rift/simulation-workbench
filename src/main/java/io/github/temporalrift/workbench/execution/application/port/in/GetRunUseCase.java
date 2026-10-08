package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

/** Reads a run's durable progress. */
public interface GetRunUseCase {

    RunView handle(UUID runId);
}
