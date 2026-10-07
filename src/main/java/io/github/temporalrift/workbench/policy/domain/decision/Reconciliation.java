package io.github.temporalrift.workbench.policy.domain.decision;

/** What the service's accepted state says about a seat's submission in the open window. */
public sealed interface Reconciliation {

    /** The service holds this accepted submission for the seat. */
    record Accepted(Candidate candidate) implements Reconciliation {}

    /** The accepted state is current and holds no submission for the seat. */
    record NotAccepted() implements Reconciliation {}

    /** The accepted state is not yet current; neither conclusion may be drawn. */
    record Pending() implements Reconciliation {}
}
