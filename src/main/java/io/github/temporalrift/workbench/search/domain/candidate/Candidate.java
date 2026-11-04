package io.github.temporalrift.workbench.search.domain.candidate;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;

/**
 * One configuration under test: a point of the parameter grid, the overrides it stands for, and the digest of the
 * effective rules they make.
 *
 * @param id names the candidate for the whole campaign, in generation order
 * @param round the round that generated it
 * @param point one grid index per parameter
 * @param overrides what differs from the baseline, as materialisation normalised it
 * @param redraws draws thrown away before this one was valid and new
 */
public record Candidate(
        String id,
        int round,
        List<Integer> point,
        SortedMap<String, BigDecimal> overrides,
        String effectiveRulesDigest,
        int redraws) {

    public Candidate {
        point = List.copyOf(point);
    }

    /** The id of the candidate generated at {@code index} in the campaign. */
    public static String idOf(int index) {
        return "c-%04d".formatted(index);
    }
}
