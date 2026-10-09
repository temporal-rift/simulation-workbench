package io.github.temporalrift.workbench.execution;

import java.util.UUID;

/** A run and the frozen experiment it executes. */
public record RunSummary(UUID runId, UUID experimentId) {}
