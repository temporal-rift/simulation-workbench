package io.github.temporalrift.workbench.analysis.domain.game;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The reconciled authoritative ending of a succeeded case.
 *
 * @param scores final score per seat, indexed by seat
 */
public record CaseOutcome(
        EndReason endReason, Set<Integer> winnerSeats, List<Integer> scores, int eras, int rounds, int decisions) {

    public CaseOutcome {
        Objects.requireNonNull(endReason, "endReason");
        winnerSeats = Set.copyOf(winnerSeats);
        scores = List.copyOf(scores);
    }
}
