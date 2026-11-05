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
     * Evaluates the variant of the run in {@code CONFIRMATION} mode, exactly as {@code getRunEvaluation} does.
     *
     * @return {@code REJECTED}, {@code CONFIRMED}, {@code UNCONFIRMED} or {@code INVALID} with the score, which is
     *     null when invalid
     * @throws IllegalArgumentException when the criterion, the run or the variant is unknown
     */
    Result confirm(int criterionVersion, UUID runId, String variantLabel);

    /**
     * @param score null when the evaluation is invalid; lower is better
     */
    record Result(String verdict, Double score) {}
}
