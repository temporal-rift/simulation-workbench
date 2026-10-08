package io.github.temporalrift.workbench.execution.domain.reproduction;

/** The reproduction an idempotency key was claimed for, with the identity of the request that claimed it. */
public record ReproductionClaim(Reproduction reproduction, String requestHash) {}
