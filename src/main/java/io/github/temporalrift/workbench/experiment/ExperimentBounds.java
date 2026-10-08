package io.github.temporalrift.workbench.experiment;

/** The execution bounds of a frozen manifest. */
public record ExperimentBounds(
        String manifestDigest, int concurrency, int caseWallTimeoutSeconds, int maxRejectedCandidatesPerWindow) {}
