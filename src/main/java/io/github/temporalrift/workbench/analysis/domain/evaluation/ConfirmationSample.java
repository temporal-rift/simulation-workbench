package io.github.temporalrift.workbench.analysis.domain.evaluation;

/**
 * The confirmation sample: at least the criterion's minimum seeds, and the full case matrix for every policy the
 * criterion names.
 */
public record ConfirmationSample(int requiredSeeds, int seeds, boolean fullMatrix) {

    public boolean satisfied() {
        return seeds >= requiredSeeds && fullMatrix;
    }
}
