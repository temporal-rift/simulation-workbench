package io.github.temporalrift.workbench.policy.domain.baseline;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;

/** A versioned policy: its identity, the digest of its canonical definition, and its behavior. */
public record PolicyBundle(String id, String version, String artifactDigest, BotPolicy policy) {}
