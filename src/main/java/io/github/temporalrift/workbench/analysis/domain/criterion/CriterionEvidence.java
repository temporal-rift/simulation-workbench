package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.Set;

import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

/** What a criterion needs of a run before it can reject or confirm a configuration. */
public record CriterionEvidence(
        double confidenceLevel,
        double executionFailedShareLimit,
        Set<FailureCode> invalidatingFailureCodes,
        Set<EndReason> invalidatingEndReasons,
        int confirmationMinimumSeeds) {

    /** What the evaluator applies: its resampling is a 95% interval, and these are the causes it can detect. */
    private static final double SUPPORTED_CONFIDENCE = 0.95;

    private static final Set<FailureCode> SUPPORTED_FAILURE_CODES =
            Set.of(FailureCode.CONTRACT_MISMATCH, FailureCode.CONFIGURATION_DRIFT);
    private static final Set<EndReason> SUPPORTED_END_REASONS = Set.of(EndReason.ALL_PLAYERS_ABANDONED);

    public CriterionEvidence {
        invalidatingFailureCodes = Set.copyOf(invalidatingFailureCodes);
        invalidatingEndReasons = Set.copyOf(invalidatingEndReasons);
        if (confidenceLevel != SUPPORTED_CONFIDENCE) {
            throw new IllegalArgumentException("Only a confidence level of " + SUPPORTED_CONFIDENCE + " is supported");
        }
        if (!SUPPORTED_FAILURE_CODES.containsAll(invalidatingFailureCodes)
                || !SUPPORTED_END_REASONS.containsAll(invalidatingEndReasons)) {
            throw new IllegalArgumentException("Only some failure codes and end reasons can invalidate a run");
        }
        if (confirmationMinimumSeeds < 2) {
            throw new IllegalArgumentException("A confirmation needs at least two seeds");
        }
    }
}
