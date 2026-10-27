package io.github.temporalrift.workbench.fidelity.application.command;

/**
 * The execution bounds of the experiment a check freezes.
 *
 * @param caseWallTimeoutSeconds how long one simulator case may take
 * @param maxRejectedCandidatesPerWindow how many refused candidates a decision window may spend
 * @param reproductionBatch how many reproductions one step of a check requests
 * @param maxConcurrency the most cases the experiment may play at once
 */
public record FidelitySettings(
        int caseWallTimeoutSeconds, int maxRejectedCandidatesPerWindow, int reproductionBatch, int maxConcurrency) {

    public FidelitySettings {
        if (caseWallTimeoutSeconds < 1 || maxRejectedCandidatesPerWindow < 1 || reproductionBatch < 1) {
            throw new IllegalArgumentException("fidelity settings must be positive");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
    }
}
