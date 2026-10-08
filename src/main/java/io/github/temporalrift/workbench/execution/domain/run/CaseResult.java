package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;
import java.util.Objects;

/**
 * The reconciled authoritative outcome of a case. An ending without winners ({@code
 * RESOLUTION_FAILED}, {@code ALL_PLAYERS_ABANDONED}, {@code DECK_EXHAUSTED}) is a valid game result
 * with its end reason, not a failure.
 */
public record CaseResult(
        EndReason endReason,
        List<Winner> winners,
        List<FinalScore> finalScores,
        int eras,
        int rounds,
        int decisions,
        String semanticDigest) {

    public CaseResult {
        Objects.requireNonNull(endReason, "endReason");
        winners = List.copyOf(winners);
        finalScores = List.copyOf(finalScores);
        if (finalScores.isEmpty()) {
            throw new IllegalArgumentException("a result needs final score evidence");
        }
        Objects.requireNonNull(semanticDigest, "semanticDigest");
    }
}
