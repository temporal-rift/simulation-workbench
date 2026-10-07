package io.github.temporalrift.workbench.policy.domain.port.out;

import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;

/**
 * The participant operations a policy decides through, bound to one case. The durable runner
 * implements it over the generated participant clients; it never exposes control operations.
 */
public interface ParticipantGateway {

    /** The seat's current entitled observation of the open window. */
    EntitledObservation observe(int seatIndex);

    /** Submits the candidate as the seat's authenticated participant. */
    SubmissionOutcome submit(int seatIndex, Candidate candidate);

    /** Reads the seat's accepted state for the open window. */
    Reconciliation reconcile(int seatIndex);
}
