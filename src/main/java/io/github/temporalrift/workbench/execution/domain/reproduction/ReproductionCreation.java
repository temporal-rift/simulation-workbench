package io.github.temporalrift.workbench.execution.domain.reproduction;

/** Whether a reproduction was stored, or its idempotency key already belonged to an earlier request. */
public sealed interface ReproductionCreation {

    record Created() implements ReproductionCreation {}

    record Existing(ReproductionClaim claim) implements ReproductionCreation {}
}
