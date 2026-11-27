package io.github.temporalrift.workbench.policy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public API of the policy module: resolves manifest policy references against defined bundles. */
public interface PolicyCatalog {

    /** Every bundle a manifest may reference. */
    List<PolicyDescriptor> list();

    PolicyReferenceVerdict verify(String id, String version, String artifactDigest, int parameterCount);

    /**
     * The artifact digest of a parameterized bundle playing these parameters, or empty when the bundle is unknown or a
     * parameter is outside its schema, range or step.
     */
    default Optional<String> artifactDigest(String id, String version, Map<String, Object> parameters) {
        return Optional.empty();
    }

    /** Every served version of the policy parameter space, ascending; empty without a parameterized bundle. */
    default List<PolicyParameterSpaceDescriptor> parameterSpaces() {
        return List.of();
    }

    /**
     * Resolves an entry that names parameters. A bundle that declares none refuses any; a parameterized bundle accepts
     * only declared parameters, each an integer in its range and on its step, and its digest must be the one its
     * parameters derive.
     */
    default PolicyVerification verify(
            String id, String version, String artifactDigest, Map<String, Object> parameters) {
        return new PolicyVerification(verify(id, version, artifactDigest, parameters.size()), null);
    }
}
