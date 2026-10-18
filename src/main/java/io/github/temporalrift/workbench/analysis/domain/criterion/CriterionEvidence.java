package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.Set;

import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.FailureCode;

/** What a criterion needs of a run before it can reject or confirm a configuration. */
public record CriterionEvidence(
        double confidenceLevel,
        double executionFailedShareLimit,
        Set<FailureCode> invalidatingFailureCodes,
        Set<EndReason> invalidatingEndReasons,
        int confirmationMinimumSeeds) {

    public CriterionEvidence {
        invalidatingFailureCodes = Set.copyOf(invalidatingFailureCodes);
        invalidatingEndReasons = Set.copyOf(invalidatingEndReasons);
        if (confirmationMinimumSeeds < 2) {
            throw new IllegalArgumentException("A confirmation needs at least two seeds");
        }
    }
}
