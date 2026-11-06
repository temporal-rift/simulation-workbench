package io.github.temporalrift.workbench.search.domain.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Driven port to the analysis module: what the balance criterion says of candidates and of how they compare. */
public interface CandidateEvaluation {

    /** Whether the balance criterion version is served. */
    boolean servesCriterion(int criterionVersion);

    /**
     * The seeds a confirmation sample needs under the criterion version.
     *
     * @throws IllegalArgumentException when the criterion is not served
     */
    int confirmationSeedCount(int criterionVersion);

    /**
     * The bot policies a confirmation sample must contain: the ones the criterion names.
     *
     * @throws IllegalArgumentException when the criterion is not served
     */
    java.util.List<io.github.temporalrift.workbench.search.domain.campaign.PolicyRef> confirmationPolicies(
            int criterionVersion);

    /**
     * Evaluates a confirmation run's variant in {@code CONFIRMATION} mode.
     *
     * @throws IllegalArgumentException when the criterion, run or variant is unknown
     */
    Evaluation confirm(int criterionVersion, UUID runId, String variantLabel);

    /**
     * Screens every candidate over all of its games, which may lie in several runs, and finds which candidates
     * dominate which on the seeds they share.
     */
    Screening screen(int criterionVersion, List<CandidateGames> candidates);

    /** A candidate and the run variants whose cases are its games. */
    record CandidateGames(String candidateId, List<WaveGames> games) {

        public CandidateGames {
            games = List.copyOf(games);
        }
    }

    /** The cases of one variant of one run. */
    record WaveGames(UUID runId, String variantLabel) {}

    /**
     * @param evaluations by candidate id
     * @param dominators by candidate id, the candidates that dominate it with evidence, in id order
     */
    record Screening(Map<String, Evaluation> evaluations, Map<String, List<String>> dominators) {

        public Screening {
            evaluations = Map.copyOf(evaluations);
            dominators = Map.copyOf(dominators);
        }
    }

    /**
     * @param verdict {@code REJECTED}, {@code CONFIRMED}, {@code UNCONFIRMED} or {@code INVALID}
     * @param score null when the evaluation is invalid; lower is better
     */
    record Evaluation(String verdict, Double score, List<String> failingGateLineIds) {

        public Evaluation {
            failingGateLineIds = List.copyOf(failingGateLineIds);
        }
    }
}
