package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.List;

import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

/**
 * What a line is split by: one line per faction, one end reason, one quantile, or the failure codes it sums. Null or
 * false means the line is not split by it.
 */
public record CriterionDimension(
        boolean eachFaction, EndReason endReason, Double quantile, List<FailureCode> failureCodes) {

    public CriterionDimension {
        failureCodes = failureCodes == null ? null : List.copyOf(failureCodes);
    }
}
