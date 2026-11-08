package io.github.temporalrift.workbench.analysis;

import java.util.UUID;

/** Lets another module confirm a run variant against a balance criterion on a confirmation sample. */
public interface RunConfirmation {

    /**
     * The seeds a confirmation sample needs under the criterion version.
     *
     * @throws IllegalArgumentException when the criterion is unknown
     */
    int minimumSeeds(int criterionVersion);

    /**
     * The bot policies the criterion names, each as an id and a major version: what a confirmation sample must
     * contain.
     *
     * @throws IllegalArgumentException when the criterion is unknown
     */
    java.util.List<PolicyRef> policies(int criterionVersion);

    /**
     * The tables the criterion's search plays: each seat of each names a policy as an id and a major version.
     *
     * @throws IllegalArgumentException when the criterion is unknown
     */
    java.util.List<Table> screeningTables(int criterionVersion);

    /**
     * The tables the criterion's confirmation plays, in criterion order: each seat of each names a policy as an id and
     * a major version.
     *
     * @throws IllegalArgumentException when the criterion is unknown
     */
    java.util.List<Table> confirmationTables(int criterionVersion);

    /**
     * Evaluates the variant of the run in {@code CONFIRMATION} mode, exactly as {@code getRunEvaluation} does.
     *
     * @return {@code REJECTED}, {@code CONFIRMED}, {@code UNCONFIRMED} or {@code INVALID} with the score, which is
     *     null when invalid
     * @throws IllegalArgumentException when the criterion, the run or the variant is unknown
     */
    Result confirm(int criterionVersion, UUID runId, String variantLabel);

    /**
     * @param seats the policy of each of the five seats, indexed by seat
     */
    record Table(String id, java.util.List<PolicyRef> seats) {}

    /**
     * @param majorVersion the major version of the policy, as in {@code 1} for {@code 1.2.0}
     */
    record PolicyRef(String id, String majorVersion) {}

    /**
     * @param score null when the evaluation is invalid; lower is better
     */
    record Result(String verdict, Double score) {}
}
