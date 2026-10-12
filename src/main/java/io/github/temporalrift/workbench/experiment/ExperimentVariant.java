package io.github.temporalrift.workbench.experiment;

/** One rules variant of a frozen experiment with the effective digests of the bundle it must run under. */
public record ExperimentVariant(String label, String effectiveRulesDigest, String effectiveContentDigest) {}
