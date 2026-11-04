package io.github.temporalrift.workbench.search.domain.campaign;

/**
 * The knobs of successive halving.
 *
 * @param initialCandidates candidates generated for round 0
 * @param screeningSeeds seeds every candidate of round 0 plays
 * @param eta a round keeps the best {@code 1/eta} of its survivors, and the next round plays {@code eta} times as
 *     many seeds
 * @param shortListSize survivors that end up on the short list
 */
public record SearchAlgorithm(int initialCandidates, int screeningSeeds, int eta, int shortListSize) {

    public SearchAlgorithm {
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
}
