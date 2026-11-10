package io.github.temporalrift.workbench.search.domain.campaign;

/**
 * The knobs of successive halving and the version of the search that turns them into rounds.
 *
 * @param version how the search generates, ranks and stops; a campaign stored before versions existed is version 1
 * @param initialCandidates candidates generated for round 0
 * @param screeningSeeds seeds every candidate of round 0 plays
 * @param eta a round keeps the best {@code 1/eta} of its candidates, and the next round plays {@code eta} times as
 *     many seeds
 * @param shortListSize candidates that end up on the short list
 */
public record SearchAlgorithm(Version version, int initialCandidates, int screeningSeeds, int eta, int shortListSize) {

    public SearchAlgorithm {
        version = version == null ? Version.SUCCESSIVE_HALVING_V1 : version;
        if (initialCandidates < 2 || screeningSeeds < 2 || eta < 2 || shortListSize < 1) {
            throw new IllegalArgumentException("a search needs at least 2 candidates, 2 seeds, eta 2 and a short list");
        }
    }

    /** The seeds every candidate of the round has played once the round is done. */
    public int seedsInRound(int round) {
        var seeds = (long) screeningSeeds;
        for (var step = 0; step < round; step++) {
            seeds *= eta;
            if (seeds > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "round " + round + " would play more seeds than a campaign can hold");
            }
        }
        return (int) seeds;
    }

    /** How many of a round's {@code candidates} the round keeps: the best {@code 1/eta}, at least one. */
    public int keptOf(int candidates) {
        return Math.max(1, Math.ceilDiv(candidates, eta));
    }

    /**
     * The candidates version 2 plays in {@code round}: {@code initialCandidates} in round 0, then the kept share of
     * the round before.
     */
    public int candidatesInRound(int round) {
        var candidates = initialCandidates;
        for (var step = 0; step < round; step++) {
            candidates = keptOf(candidates);
        }
        return candidates;
    }

    /** The last round of the version 2 plan: the first that keeps at most {@code shortListSize} candidates. */
    public int lastRound() {
        var round = 0;
        while (keptOf(candidatesInRound(round)) > shortListSize) {
            round++;
        }
        return round;
    }

    /** How a search generates, ranks and stops. */
    public enum Version {
        /**
         * Round 0 is the baseline and one-step perturbations; later rounds add mutations; a failing gate discards;
         * the search stops on its time budget or a stable short list.
         */
        SUCCESSIVE_HALVING_V1,
        /**
         * Round 0 is the baseline and a space-filling sample; later rounds add nothing; a failing gate ranks a
         * candidate by its gate miss instead of discarding it; the search ends after {@link #lastRound()}.
         */
        SUCCESSIVE_HALVING_V2
    }
}
