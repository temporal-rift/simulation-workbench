package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.List;
import java.util.Map;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * The result of one hunt cell: one faction at one table size.
 *
 * <p>A cell without a flag is evidence, not a guarantee, that no exploit exists, and the results characterize bot
 * strategies only: they say nothing about balance among human players.
 *
 * @param confirmation null while nothing is confirmed
 * @param limitations what the reader must not conclude from the cell
 */
public record HuntReport(
        Faction faction,
        int playerCount,
        Screening screening,
        Classification classification,
        Confirmed confirmation,
        List<String> limitations) {

    /** The statements every report carries. */
    public static final List<String> LIMITATIONS = List.of(
            "A cell without a flag is evidence, not a guarantee, that no exploit exists: the search covers the"
                    + " strategies a preference table can express.",
            "Results characterize bot strategies only and say nothing about balance among human players.");

    public HuntReport {
        limitations = List.copyOf(limitations);
    }

    /**
     * What the search measured.
     *
     * @param candidates the candidates screened in round 0
     * @param screeningRatioOfBest the best candidate's win ratio over its games of the last round
     * @param noise the standard deviation of the round-0 win ratios of the candidates, every one on the same seeds
     * @param shareAboveFlagLine the share of round-0 candidates whose ratio is at least the flag factor
     */
    public record Screening(int candidates, double screeningRatioOfBest, double noise, double shareAboveFlagLine) {}

    /** The confirmation of the best candidate on fresh seeds. */
    public record Confirmed(
            double candidateRatio,
            double lower,
            double upper,
            double standardRatio,
            double normalRatio,
            Map<String, Integer> parameters) {

        public Confirmed {
            parameters = Map.copyOf(parameters);
        }
    }

    /** What the cell is. */
    public enum Classification {
        NOT_CONFIRMED,
        BALANCE_NOISE,
        FLAGGED,
        EXPLOIT;

        /**
         * The class of a confirmed cell: the normal ratio is the larger of 1.0 and the standard seat's ratio; a
         * candidate at least {@link HuntPlan#EXPLOIT_FACTOR} times it is an exploit, at least
         * {@link HuntPlan#FLAG_FACTOR} times it is flagged, and anything else is balance noise.
         */
        public static Classification of(double candidateRatio, double standardRatio) {
            var normal = normalRatio(standardRatio);
            if (candidateRatio >= HuntPlan.EXPLOIT_FACTOR * normal) {
                return EXPLOIT;
            }
            if (candidateRatio >= HuntPlan.FLAG_FACTOR * normal) {
                return FLAGGED;
            }
            return BALANCE_NOISE;
        }
    }

    /** The larger of the fair ratio and the standard seat's own ratio on the same seeds. */
    public static double normalRatio(double standardRatio) {
        return Math.max(HuntPlan.FAIR_RATIO, standardRatio);
    }

    /** The report of a cell whose best candidate did not pass the screening gate or is not confirmed yet. */
    public static HuntReport unconfirmed(Faction faction, int playerCount, Screening screening) {
        return new HuntReport(faction, playerCount, screening, Classification.NOT_CONFIRMED, null, LIMITATIONS);
    }
}
