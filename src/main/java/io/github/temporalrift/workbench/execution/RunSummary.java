package io.github.temporalrift.workbench.execution;

import java.util.UUID;

import io.github.temporalrift.workbench.shared.execution.RunPhase;

/**
 * A run, the frozen experiment it executes, and where it stands.
 *
 * @param failureMessage why the run failed; null unless it failed
 */
public record RunSummary(UUID runId, UUID experimentId, RunPhase phase, String failureMessage) {}
