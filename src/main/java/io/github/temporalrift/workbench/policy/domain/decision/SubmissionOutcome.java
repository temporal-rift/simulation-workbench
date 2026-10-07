package io.github.temporalrift.workbench.policy.domain.decision;

/** What the authoritative service answered to a submitted candidate. */
public sealed interface SubmissionOutcome {

    /** The service accepted the submission. */
    record Accepted() implements SubmissionOutcome {}

    /** The service definitively rejected the submission with the given problem code. */
    record Rejected(String code) implements SubmissionOutcome {}

    /** No definitive answer arrived (a lost response, or only pending projection data). */
    record Unacknowledged() implements SubmissionOutcome {}
}
