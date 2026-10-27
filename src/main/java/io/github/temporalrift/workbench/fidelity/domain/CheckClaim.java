package io.github.temporalrift.workbench.fidelity.domain;

/** The check an idempotency key was claimed for, with the identity of the request that claimed it. */
public record CheckClaim(FidelityCheck check, String requestHash) {}
