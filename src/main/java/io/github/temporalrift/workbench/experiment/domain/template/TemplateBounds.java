package io.github.temporalrift.workbench.experiment.domain.template;

import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;

/** The execution bounds every template proposes, within the bounds a manifest accepts. */
public record TemplateBounds(int caseWallTimeoutSeconds, int maxRejectedCandidatesPerWindow) {

    public TemplateBounds {
        requireWithin(
                "case-wall-timeout-seconds", caseWallTimeoutSeconds, ExperimentValidator.MAX_CASE_TIMEOUT_SECONDS);
        requireWithin(
                "max-rejected-candidates-per-window",
                maxRejectedCandidatesPerWindow,
                ExperimentValidator.MAX_REJECTED_CANDIDATES);
    }

    private static void requireWithin(String key, int value, int max) {
        if (value < 1 || value > max) {
            throw new IllegalArgumentException(
                    "workbench.experiment.templates." + key + " must be from 1 to " + max + ": " + value);
        }
    }
}
