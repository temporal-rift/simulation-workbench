package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.criterion.Band;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * One criterion line expanded for one cohort and dimension value.
 *
 * @param factionSet null when the cohort pools every faction set
 * @param deviation 0 inside the band, and for a line without data
 * @param weight null for a gate
 */
public record EvaluatedLine(
        String lineId,
        CriterionRole role,
        String metric,
        LineDimensions dimensions,
        String policyId,
        int playerCount,
        List<Faction> factionSet,
        Band band,
        Double value,
        Interval interval,
        MetricStatus status,
        double deviation,
        LineVerdict verdict,
        Double weight) {

    public EvaluatedLine {
        factionSet = factionSet == null ? null : List.copyOf(factionSet);
    }

    public boolean pooled() {
        return factionSet == null;
    }
}
