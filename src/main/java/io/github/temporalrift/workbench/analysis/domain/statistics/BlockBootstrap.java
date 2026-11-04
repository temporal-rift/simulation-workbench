package io.github.temporalrift.workbench.analysis.domain.statistics;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

import io.github.temporalrift.workbench.shared.random.SplitMix64;

/**
 * Resampling plans over a cohort's independent blocks: each of the {@value #RESAMPLES} plans draws as many blocks
 * as the cohort has, with replacement, and every statistic of the cohort is recomputed under the same plans.
 */
public final class BlockBootstrap {

    public static final int RESAMPLES = 2000;

    private static final double LOWER = 0.025;
    private static final double UPPER = 0.975;

    private final int[][] plans;

    private BlockBootstrap(int[][] plans) {
        this.plans = plans;
    }

    public static BlockBootstrap of(int blocks, SplitMix64 stream) {
        var plans = new int[RESAMPLES][blocks];
        for (var plan : plans) {
            for (var draw = 0; draw < blocks; draw++) {
                plan[stream.nextInt(blocks)]++;
            }
        }
        return new BlockBootstrap(plans);
    }

    /** Every block once: the point estimate's plan. */
    public static int[] identity(int blocks) {
        var plan = new int[blocks];
        Arrays.fill(plan, 1);
        return plan;
    }

    /**
     * The 2.5th and 97.5th nearest-rank percentiles of the statistic over the plans, skipping plans where it is
     * undefined (NaN); empty when no plan defines it.
     */
    public Optional<double[]> interval(ToDoubleFunction<int[]> statistic) {
        return interval(replicates(statistic));
    }

    /** The statistic under every plan in plan order, NaN where it is undefined. */
    public double[] replicates(ToDoubleFunction<int[]> statistic) {
        var values = new double[plans.length];
        for (var index = 0; index < plans.length; index++) {
            values[index] = statistic.applyAsDouble(plans[index]);
        }
        return values;
    }

    /**
     * The interval of replicates taken under the same plans, skipping undefined (NaN) ones; empty when none is
     * defined. Replicates of two statistics subtracted plan by plan give the interval of their paired difference.
     */
    public static Optional<double[]> interval(double[] replicates) {
        var values = new double[replicates.length];
        var defined = 0;
        for (var value : replicates) {
            if (!Double.isNaN(value)) {
                values[defined++] = value;
            }
        }
        if (defined == 0) {
            return Optional.empty();
        }
        var sorted = Arrays.copyOf(values, defined);
        Arrays.sort(sorted);
        return Optional.of(new double[] {rank(sorted, LOWER), rank(sorted, UPPER)});
    }

    private static double rank(double[] sorted, double share) {
        var rank = (int) Math.ceil(share * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }
}
