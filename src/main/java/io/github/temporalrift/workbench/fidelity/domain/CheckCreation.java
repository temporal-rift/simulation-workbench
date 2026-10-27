package io.github.temporalrift.workbench.fidelity.domain;

/** What storing a queued check led to. */
public sealed interface CheckCreation {

    /** The check was stored. */
    record Created() implements CheckCreation {}

    /** The idempotency key already belonged to an earlier request. */
    record Existing(CheckClaim claim) implements CheckCreation {}

    /** Another check is queued or running, so none was stored. */
    record Active() implements CheckCreation {}
}
