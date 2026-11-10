package io.github.temporalrift.workbench.analysis;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lets another module screen candidate configurations against a balance criterion: each candidate is evaluated over
 * all of its games, which may lie in several runs, and compared with the others on the seeds they share.
 */
public interface CandidateScreening {

    /** Whether the balance criterion version is served. */
    boolean servesCriterion(int criterionVersion);

    /** The newest balance criterion version the service serves. */
    int newestCriterionVersion();

    /**
     * Evaluates every candidate in {@code SCREENING} mode and finds which candidates dominate which. Only candidates
     * whose evaluation is neither rejected nor invalid take part in dominance, as dominated and as dominating.
     *
     * @param criterionVersion the balance criterion version to evaluate against
     * @throws IllegalArgumentException when the criterion is unknown or a candidate has no games
     */
    Screening screen(int criterionVersion, List<Candidate> candidates);

    /** A candidate and the run variants whose cases are its games. */
    record Candidate(String id, List<Games> games) {

        public Candidate {
            games = List.copyOf(games);
        }
    }

    /** The cases of one variant of one run. */
    record Games(UUID runId, String variantLabel) {}

    /**
     * @param evaluations by candidate id
     * @param dominators by candidate id, the ids of the candidates that dominate it with evidence, in id order;
     *     candidates that nobody dominates are absent
     */
    record Screening(Map<String, Evaluation> evaluations, Map<String, List<String>> dominators) {

        public Screening {
            evaluations = Map.copyOf(evaluations);
            dominators = Map.copyOf(dominators);
        }
    }

    /**
     * A candidate's evaluation as the search needs it.
     *
     * @param score null when the evaluation is invalid; lower is better
     * @param failingGateLineIds every gate line that fails, in id order
     * @param gateMiss how far the candidate misses the gates: for each gate line id, the mean deviation of that id's
     *     lines that have a value, summed over the gate line ids; 0 when no gate line deviates, null when the
     *     evaluation is invalid
     */
    record Evaluation(Verdict verdict, Double score, List<String> failingGateLineIds, Double gateMiss) {

        public Evaluation {
            failingGateLineIds = List.copyOf(failingGateLineIds);
        }
    }

    /** What the criterion says of a candidate. */
    enum Verdict {
        REJECTED,
        CONFIRMED,
        UNCONFIRMED,
        INVALID
    }
}
