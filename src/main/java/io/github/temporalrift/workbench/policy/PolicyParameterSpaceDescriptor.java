package io.github.temporalrift.workbench.policy;

import java.util.List;
import java.util.Map;

/**
 * One version of the parameter space of a parameterized policy bundle.
 *
 * @param searchedSubsets the keys a hunt of each faction (by faction name) searches
 */
public record PolicyParameterSpaceDescriptor(
        int version,
        String policyId,
        String policyVersion,
        List<Parameter> parameters,
        Map<String, List<String>> searchedSubsets) {

    /** One parameter on its grid. */
    public record Parameter(String key, int defaultValue, int min, int max, int step) {}
}
