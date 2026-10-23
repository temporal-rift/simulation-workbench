package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;

import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/** What an evaluated line is restricted to; a null dimension means the line is not split by it. */
public record LineDimensions(Faction faction, EndReason endReason, Double quantile, List<FailureCode> failureCodes) {

    public LineDimensions {
        failureCodes = failureCodes == null ? null : List.copyOf(failureCodes);
    }
}
