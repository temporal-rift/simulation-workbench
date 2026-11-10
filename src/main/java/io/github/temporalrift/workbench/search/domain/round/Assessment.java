package io.github.temporalrift.workbench.search.domain.round;

import java.util.List;

import io.github.temporalrift.workbench.search.domain.candidate.Candidate;

/**
 * What the balance criterion says of one candidate after its games of a round.
 *
 * @param seeds the seeds the candidate has played so far
 * @param gamesSpent games the candidate has played so far
 * @param verdict what screening concluded
 * @param score the weighted squared deviation from the balance bands, lower is better; null for an invalid evaluation
 * @param failingGateLineIds every gate line that fails, in id order
 * @param gateMiss how far the candidate misses the gates, each gate counted once; null for an invalid evaluation
 * @param dominators the candidates that dominate this one with evidence, in id order
 */
public record Assessment(
        Candidate candidate,
        List<String> seeds,
        int gamesSpent,
        Verdict verdict,
        Double score,
        List<String> failingGateLineIds,
        Double gateMiss,
        List<String> dominators) {

    public Assessment {
        seeds = List.copyOf(seeds);
        failingGateLineIds = List.copyOf(failingGateLineIds);
        dominators = List.copyOf(dominators);
    }

    /** Whether no gate line of the candidate's evaluation fails. */
    public boolean passesEveryGate() {
        return failingGateLineIds.isEmpty() && verdict != Verdict.REJECTED;
    }

    /** What screening concluded about a candidate. */
    public enum Verdict {
        REJECTED,
        UNCONFIRMED,
        CONFIRMED,
        INVALID
    }
}
