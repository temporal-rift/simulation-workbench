package io.github.temporalrift.workbench.execution.domain.run;

/** The result of creating a run under an idempotency key. */
public sealed interface RunCreation {

    /** The run and its cases were stored. */
    record Created() implements RunCreation {}

    /** The key was already claimed; nothing was stored. */
    record Existing(RunCommand claim) implements RunCreation {}
}
