package io.github.temporalrift.workbench.analysis.domain.game;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.temporalrift.workbench.shared.game.EndReason;

/**
 * The reconciled authoritative ending of a succeeded case.
 *
 * @param scores final score per seat, indexed by seat
 * @param winPaths how each winner won, by seat; a winner without an entry has an unknown path
 */
public record CaseOutcome(
        EndReason endReason,
        Set<Integer> winnerSeats,
        List<Integer> scores,
        int eras,
        int rounds,
        int decisions,
        Map<Integer, WinPath> winPaths) {

    public CaseOutcome {
        Objects.requireNonNull(endReason, "endReason");
        winnerSeats = Set.copyOf(winnerSeats);
        scores = List.copyOf(scores);
        winPaths = winPaths == null ? Map.of() : Map.copyOf(winPaths);
    }

    /** An outcome whose winners' paths are not known. */
    public CaseOutcome(
            EndReason endReason, Set<Integer> winnerSeats, List<Integer> scores, int eras, int rounds, int decisions) {
        this(endReason, winnerSeats, scores, eras, rounds, decisions, Map.of());
    }
}
