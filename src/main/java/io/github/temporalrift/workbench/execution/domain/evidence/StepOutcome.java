package io.github.temporalrift.workbench.execution.domain.evidence;

/** What the service did with a seat's command. */
public enum StepOutcome {
    /** The service holds the command; it is part of the decision transcript. */
    ACCEPTED,
    /** The service refused the command; nothing was spent. */
    REJECTED,
    /** The answer was lost; the step is resolved once accepted state is read. */
    UNACKNOWLEDGED,
    /** Accepted state confirmed the command was never spent. */
    NOT_SPENT
}
