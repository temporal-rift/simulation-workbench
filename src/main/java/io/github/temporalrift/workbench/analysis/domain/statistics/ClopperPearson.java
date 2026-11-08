package io.github.temporalrift.workbench.analysis.domain.statistics;

/**
 * The exact two-sided Clopper–Pearson interval of a binomial share: the bounds are quantiles of the beta distribution,
 * found by bisection on the regularized incomplete beta function. With no successes the lower bound is 0, and with
 * only successes the upper bound is 1.
 */
public final class ClopperPearson {

    private static final int BISECTION_STEPS = 100;
    private static final int CONTINUED_FRACTION_TERMS = 300;
    private static final double EPSILON = 1e-15;
    private static final double TINY = 1e-300;

    private static final double[] LANCZOS = {
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7
    };

    private ClopperPearson() {}

    /**
     * @param confidence the two-sided coverage, such as 0.95
     * @return the lower and upper bound
     * @throws IllegalArgumentException when there are no trials or the counts are inconsistent
     */
    public static double[] interval(long successes, long trials, double confidence) {
        if (trials <= 0 || successes < 0 || successes > trials) {
            throw new IllegalArgumentException(
                    "A Clopper-Pearson interval needs 0 <= successes <= trials and trials > 0");
        }
        var tail = (1 - confidence) / 2;
        var lower = successes == 0 ? 0.0 : betaQuantile(tail, successes, trials - successes + 1.0);
        var upper = successes == trials ? 1.0 : betaQuantile(1 - tail, successes + 1.0, (double) trials - successes);
        return new double[] {lower, upper};
    }

    /** The {@code p} quantile of Beta(a, b). */
    static double betaQuantile(double p, double a, double b) {
        var low = 0.0;
        var high = 1.0;
        for (var step = 0; step < BISECTION_STEPS; step++) {
            var middle = (low + high) / 2;
            if (regularizedBeta(middle, a, b) < p) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) / 2;
    }

    /** I_x(a, b), the cumulative distribution function of Beta(a, b) at x. */
    static double regularizedBeta(double x, double a, double b) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        var front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log1p(-x));
        if (x < (a + 1) / (a + b + 2)) {
            return front * continuedFraction(x, a, b) / a;
        }
        return 1 - front * continuedFraction(1 - x, b, a) / b;
    }

    /** The continued fraction of the incomplete beta function, by the modified Lentz method. */
    private static double continuedFraction(double x, double a, double b) {
        var c = 1.0;
        var d = nonZero(1 - (a + b) * x / (a + 1));
        d = 1 / d;
        var result = d;
        for (var m = 1; m <= CONTINUED_FRACTION_TERMS; m++) {
            var even = m * (b - m) * x / ((a + 2 * m - 1) * (a + 2 * m));
            d = 1 / nonZero(1 + even * d);
            c = nonZero(1 + even / c);
            result *= d * c;
            var odd = -(a + m) * (a + b + m) * x / ((a + 2 * m) * (a + 2 * m + 1));
            d = 1 / nonZero(1 + odd * d);
            c = nonZero(1 + odd / c);
            var delta = d * c;
            result *= delta;
            if (Math.abs(delta - 1) < EPSILON) {
                return result;
            }
        }
        return result;
    }

    private static double nonZero(double value) {
        return Math.abs(value) < TINY ? TINY : value;
    }

    /** ln Γ(x) for x > 0, by the Lanczos approximation. */
    static double logGamma(double x) {
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
        }
        var shifted = x - 1;
        var sum = LANCZOS[0];
        for (var index = 1; index < LANCZOS.length; index++) {
            sum += LANCZOS[index] / (shifted + index);
        }
        var t = shifted + LANCZOS.length - 1.5;
        return 0.5 * Math.log(2 * Math.PI) + (shifted + 0.5) * Math.log(t) - t + Math.log(sum);
    }
}
