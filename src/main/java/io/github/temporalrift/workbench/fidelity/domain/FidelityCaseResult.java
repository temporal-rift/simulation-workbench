package io.github.temporalrift.workbench.fidelity.domain;

/** What a case's reproduction on the real lane showed. */
public enum FidelityCaseResult {
    /** Not reproduced yet. */
    PENDING,
    /** The real lane played the case exactly as the simulator did. */
    MATCH,
    /** The real lane ran the case to its end and differed. */
    DIVERGED,
    /** The case could not be played on the simulator or could not be reproduced to an end. */
    UNREPRODUCIBLE
}
