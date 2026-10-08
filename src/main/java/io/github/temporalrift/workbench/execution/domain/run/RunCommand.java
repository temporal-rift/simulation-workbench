package io.github.temporalrift.workbench.execution.domain.run;

import java.util.UUID;

/** An idempotency claim over a run command. */
public record RunCommand(UUID idempotencyKey, String operation, UUID runId, String requestHash) {}
