package io.github.temporalrift.workbench.policy;

import java.util.List;

/** Public API of the policy module: resolves manifest policy references against defined bundles. */
public interface PolicyCatalog {

    /** Every bundle a manifest may reference. */
    List<PolicyDescriptor> list();

    PolicyReferenceVerdict verify(String id, String version, String artifactDigest, int parameterCount);
}
