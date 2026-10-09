package io.github.temporalrift.workbench.analysis.domain.comparison;

/** Why a pair stays out of the paired estimates. */
public enum ExclusionReason {
    /** A side failed or was cancelled. */
    FAILED_COUNTERPART,
    /** A side is still pending or running. */
    UNFINISHED
}
