package io.github.temporalrift.workbench.policy;

import java.util.List;

/** Public API of the policy module: resolves manifest policy references against defined bundles. */
public interface PolicyCatalog {

    /** Every bundle a manifest may reference. */
    List<PolicyDescriptor> list();

    PolicyReferenceVerdict verify(String id, String version, String artifactDigest, int parameterCount);

    /**
     * Resolves an entry that names parameters. A bundle that declares none refuses any; a parameterized bundle accepts
     * only declared parameters, each an integer in its range and on its step, and its digest must be the one its
     * parameters derive.
     */
    default PolicyVerification verify(
            String id, String version, String artifactDigest, java.util.Map<String, Object> parameters) {
        return new PolicyVerification(verify(id, version, artifactDigest, parameters.size()), null);
    }
}
