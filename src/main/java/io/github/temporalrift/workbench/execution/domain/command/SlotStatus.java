package io.github.temporalrift.workbench.execution.domain.command;

/** What is known about a decision slot's command. */
public enum SlotStatus {
    /** Sent; the service's answer is unknown. */
    SENT,
    /** The service holds the command. */
    ACCEPTED,
    /** Definitively not spent: rejected, or confirmed absent from current accepted state. */
    NOT_SPENT
}
