package io.github.temporalrift.workbench.policy;

import java.util.List;

/**
 * A policy bundle a manifest may reference.
 *
 * @param parameterDefinitions the declared parameters of a parameterized bundle; empty for one that accepts none
 */
public record PolicyDescriptor(
        String id,
        String version,
        String artifactDigest,
        String description,
        List<PolicyParameterSpaceDescriptor.Parameter> parameterDefinitions) {

    public PolicyDescriptor {
        parameterDefinitions = List.copyOf(parameterDefinitions);
    }

    public PolicyDescriptor(String id, String version, String artifactDigest, String description) {
        this(id, version, artifactDigest, description, List.of());
    }
}
