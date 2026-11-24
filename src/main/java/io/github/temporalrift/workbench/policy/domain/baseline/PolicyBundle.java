package io.github.temporalrift.workbench.policy.domain.baseline;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;

/**
 * A versioned policy: its identity, the behavior revision its digest follows, the digest, and its behavior. The digest
 * is the hash of the id, version and revision, never of prose.
 */
public record PolicyBundle(
        String id, String version, String revision, String artifactDigest, String description, BotPolicy policy) {}
