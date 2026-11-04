package io.github.temporalrift.workbench.analysis.domain.evaluation;

import io.github.temporalrift.workbench.analysis.domain.criterion.Band;
import io.github.temporalrift.workbench.analysis.domain.report.CohortComputation.Replicates;

/**
 * A scored line's deviation from its band at the point estimate and under each resampling plan.
 *
 * @param blocks the seed blocks resampled; deviations are only comparable between equal block counts
 * @param values null when there were too few blocks to resample; NaN where a plan leaves the line undefined
 */
record DeviationReplicates(boolean hasData, int blocks, double[] values) {

    static DeviationReplicates of(Band band, Replicates replicates) {
        var values = replicates.values();
        double[] deviations = null;
        if (values != null) {
            deviations = new double[values.length];
            for (var index = 0; index < values.length; index++) {
                deviations[index] = Double.isNaN(values[index]) ? Double.NaN : band.deviation(values[index]);
            }
        }
        return new DeviationReplicates(!Double.isNaN(replicates.point()), replicates.blocks(), deviations);
    }
}
