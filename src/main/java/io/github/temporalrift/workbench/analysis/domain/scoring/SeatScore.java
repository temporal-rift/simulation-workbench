package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * The running score of one seat under a scoring engine, kept in exact millionths of a point so that equal scores
 * reached in different orders stay equal.
 *
 * <p>A seat is scored era by era: every occurrence of the era's reasons through {@link #occurrence}, then the era's bet
 * outcome through {@link #endEra}, which ends the runs of the escalations that reset on it. Banked points never change.
 */
public final class SeatScore {

    /** Millionths in one point. */
    public static final long UNITS = 1_000_000L;

    private final Pricing pricing;
    private final int[] runs;
    private long total;

    public SeatScore(Pricing pricing) {
        this.pricing = pricing;
        this.runs = new int[pricing.escalations.length];
    }

    /** What an engine prices, looked up by reason; share one across the seats and games of an evaluation. */
    public static final class Pricing {

        private final Map<String, Integer> flat = new HashMap<>();
        private final Map<String, Integer> escalationOf = new HashMap<>();
        private final Escalation[] escalations;

        public Pricing(ScoringEngine engine) {
            engine.reasonPoints().forEach(item -> flat.put(item.scoreReason(), item.points()));
            this.escalations = engine.escalations().toArray(new Escalation[0]);
            for (var index = 0; index < escalations.length; index++) {
                for (var reason : escalations[index].scoreReasons()) {
                    escalationOf.put(reason, index);
                }
            }
        }

        /** The flat points of a reason, or {@code null} when the reason is escalated or unpriced. */
        public Integer flatPoints(String reason) {
            return flat.get(reason);
        }
    }

    /**
     * Scores one occurrence of a reason: an escalated reason extends its run to n and scores
     * {@code base + increment * (n - 1)}, any other scores its flat points.
     *
     * @throws IllegalArgumentException when the engine does not price the reason
     */
    public void occurrence(String reason) {
        var escalation = pricing.escalationOf.get(reason);
        if (escalation != null) {
            var rule = pricing.escalations[escalation];
            total += (rule.base() + (long) rule.increment() * runs[escalation]) * UNITS;
            runs[escalation]++;
            return;
        }
        var points = pricing.flat.get(reason);
        if (points == null) {
            throw new IllegalArgumentException("the engine does not price " + reason);
        }
        total += points * UNITS;
    }

    /** Ends the era: every escalation that resets on the seat-era's bet outcome starts its next run from 0. */
    public void endEra(BetOutcome outcome) {
        for (var index = 0; index < runs.length; index++) {
            if (pricing.escalations[index].resetOn().contains(outcome)) {
                runs[index] = 0;
            }
        }
    }

    /** Adds points counted outside the engine's occurrences, in millionths. */
    public void addUnits(long units) {
        total += units;
    }

    /** Resolves a decimal number of points to millionths. */
    public static long toUnits(double points) {
        return Math.round(points * UNITS);
    }

    /** Resolves an exact number of points to millionths, rounding half up. */
    public static long toUnits(BigDecimal points) {
        return points.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The total in millionths. */
    public long total() {
        return total;
    }
}
