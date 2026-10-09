package io.github.temporalrift.workbench.analysis.domain.comparison;

/** A variant definition that differs between the two sides, with both effective digests. */
public record DeclaredDifference(DifferenceField field, String baselineDigest, String candidateDigest) {}
