package io.github.temporalrift.workbench.analysis.domain.statistics;

import java.util.Map;
import java.util.TreeMap;

/**
 * One statistic's per-block sums: numerator and denominator for a ratio, value counts for a quantile. A block's
 * weight in a resampling plan multiplies its sums.
 */
public final class BlockSeries {

    private final long[] numerator;
    private final long[] denominator;
    private final Map<Integer, long[]> valueCounts = new TreeMap<>();
    private long unknown;

    public BlockSeries(int blocks) {
        this.numerator = new long[blocks];
        this.denominator = new long[blocks];
    }

    public void addRatio(int block, long numeratorPart, long denominatorPart, long unknownPart) {
        numerator[block] += numeratorPart;
        denominator[block] += denominatorPart;
        unknown += unknownPart;
    }

    public void addValue(int block, int value) {
        valueCounts.computeIfAbsent(value, ignored -> new long[numerator.length])[block]++;
        denominator[block]++;
    }

    public long unknown() {
        return unknown;
    }

    public long numerator(int[] plan) {
        return weighted(numerator, plan);
    }

    public long denominator(int[] plan) {
        return weighted(denominator, plan);
    }

    /** Numerator over denominator under the plan, or NaN when the denominator is 0. */
    public double ratio(int[] plan) {
        var den = denominator(plan);
        return den == 0 ? Double.NaN : (double) numerator(plan) / den;
    }

    /** The smallest value whose weighted cumulative share reaches the quantile, or NaN without values. */
    public double quantile(int[] plan, double quantile) {
        var total = denominator(plan);
        if (total == 0) {
            return Double.NaN;
        }
        var rank = Math.max((long) Math.ceil(quantile * total), 1L);
        var cumulative = 0L;
        for (var entry : valueCounts.entrySet()) {
            cumulative += weighted(entry.getValue(), plan);
            if (cumulative >= rank) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("cumulative counts never reached the sample size");
    }

    private static long weighted(long[] perBlock, int[] plan) {
        var sum = 0L;
        for (var block = 0; block < perBlock.length; block++) {
            sum += perBlock[block] * plan[block];
        }
        return sum;
    }
}
