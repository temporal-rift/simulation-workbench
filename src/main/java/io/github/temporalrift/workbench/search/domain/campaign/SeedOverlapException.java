package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.List;

/** A confirmation seed was already played by the search, so confirming on it would confirm the same luck. */
public class SeedOverlapException extends RuntimeException {

    private final List<String> seeds;

    public SeedOverlapException(List<String> seeds) {
        super("Confirmation seeds %s already appear in the campaign log".formatted(seeds));
        this.seeds = List.copyOf(seeds);
    }

    /** The confirmation seeds that the campaign log already holds. */
    public List<String> seeds() {
        return seeds;
    }
}
