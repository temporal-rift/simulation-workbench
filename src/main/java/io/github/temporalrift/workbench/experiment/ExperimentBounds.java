package io.github.temporalrift.workbench.experiment;

import java.util.List;

/**
 * The execution bounds of a frozen manifest and its variants, with the manifest itself so that the exact input a case
 * ran under can be retained as evidence.
 */
public record ExperimentBounds(
        String manifestDigest,
        String manifestJson,
        int concurrency,
        int caseWallTimeoutSeconds,
        int maxRejectedCandidatesPerWindow,
        List<ExperimentVariant> variants) {

    public ExperimentBounds {
        variants = List.copyOf(variants);
    }
}
