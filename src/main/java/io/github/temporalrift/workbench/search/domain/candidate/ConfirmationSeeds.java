package io.github.temporalrift.workbench.search.domain.candidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import io.github.temporalrift.workbench.shared.random.SplitMix64;

/**
 * The seeds a confirmation plays. They derive from the confirmation master seed only and from a namespace of their
 * own, so no search derivation can produce them except by chance, which the overlap check catches.
 */
public final class ConfirmationSeeds {

    private final String masterSeed;

    public ConfirmationSeeds(String masterSeed) {
        this.masterSeed = Objects.requireNonNull(masterSeed, "masterSeed");
    }

    /** The first {@code count} distinct seeds as decimal unsigned 64-bit integers; a smaller count is a prefix. */
    public List<String> seeds(int count) {
        var stream = SplitMix64.seededBy("confirmation", masterSeed, "game-seeds");
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
}
