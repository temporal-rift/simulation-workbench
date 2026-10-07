package io.github.temporalrift.workbench.policy;

/** Public API of the policy module: resolves manifest policy references against defined bundles. */
public interface PolicyCatalog {

    PolicyReferenceVerdict verify(String id, String version, String artifactDigest, int parameterCount);
}
