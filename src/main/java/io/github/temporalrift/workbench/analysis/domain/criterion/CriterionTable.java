package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A named assignment of one policy reference to each seat index, and the phases that play it. A case at {@code p}
 * players uses seats {@code 0} to {@code p - 1}.
 *
 * @param seats the criterion policy reference of each seat, indexed by seat
 */
public record CriterionTable(String id, List<String> seats, Set<CriterionPhase> phases) {

    public CriterionTable {
        Objects.requireNonNull(id, "id");
        seats = List.copyOf(seats);
        phases = Set.copyOf(phases);
        if (seats.size() != 5) {
            throw new IllegalArgumentException("Table " + id + " needs a policy for each of the five seats");
        }
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("Table " + id + " needs a phase");
        }
    }

    /** Whether every seat plays one policy. */
    public boolean uniform() {
        return seats.stream().distinct().count() == 1;
    }
}
