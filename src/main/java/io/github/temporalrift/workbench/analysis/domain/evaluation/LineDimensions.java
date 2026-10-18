package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.FailureCode;

/** What an evaluated line is restricted to; a null dimension means the line is not split by it. */
public record LineDimensions(Faction faction, EndReason endReason, Double quantile, List<FailureCode> failureCodes) {

    public LineDimensions {
        failureCodes = failureCodes == null ? null : List.copyOf(failureCodes);
    }
}
