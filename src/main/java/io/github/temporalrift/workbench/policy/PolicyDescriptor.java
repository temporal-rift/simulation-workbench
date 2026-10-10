package io.github.temporalrift.workbench.policy;

/** A policy bundle a manifest may reference; the baseline bundles accept no parameters. */
public record PolicyDescriptor(String id, String version, String artifactDigest, String description) {}
