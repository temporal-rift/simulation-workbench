package io.github.temporalrift.workbench.experiment;

/**
 * The execution bounds of a frozen manifest, with the manifest itself so that the exact input a case ran
 * under can be retained as evidence.
 */
public record ExperimentBounds(
        String manifestDigest,
        String manifestJson,
        int concurrency,
        int caseWallTimeoutSeconds,
        int maxRejectedCandidatesPerWindow) {}
