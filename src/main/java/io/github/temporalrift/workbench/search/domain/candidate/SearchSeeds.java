package io.github.temporalrift.workbench.search.domain.candidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import io.github.temporalrift.workbench.shared.random.SplitMix64;

/**
 * Everything random in a search derives from the search master seed: the games' seeds and each round's draws. The
 * same master seed always gives the same seeds and the same streams.
 */
public final class SearchSeeds {

    private final String masterSeed;

    public SearchSeeds(String masterSeed) {
        this.masterSeed = Objects.requireNonNull(masterSeed, "masterSeed");
    }

    /**
     * The first {@code count} distinct game seeds as decimal unsigned 64-bit integers. They are one sequence, so
     * the seeds of a smaller count are a prefix of those of a larger one.
     */
    public List<String> gameSeeds(int count) {
        var stream = SplitMix64.seededBy("search", masterSeed, "game-seeds");
        var seen = new HashSet<Long>();
        var seeds = new ArrayList<String>(count);
        while (seeds.size() < count) {
            var seed = stream.nextLong();
            if (seen.add(seed)) {
                seeds.add(Long.toUnsignedString(seed));
            }
        }
        return seeds;
    }

    /** The stream of one kind of draw in one round, which no other round or kind shares. */
    public SplitMix64 stream(int round, String role) {
        return SplitMix64.seededBy("search", masterSeed, Integer.toString(round), role);
    }
}
