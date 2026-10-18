package io.github.temporalrift.workbench.analysis.domain.criterion;

/**
 * How a criterion names a bot policy: the policy id with the major version, as in {@code faction-greedy-v1} for
 * policy {@code faction-greedy} at version {@code 1.0.0}. A run's cohorts carry id and version separately.
 */
public final class PolicyReference {

    private PolicyReference() {}

    public static String of(String policyId, String policyVersion) {
        var dot = policyVersion.indexOf('.');
        return policyId + "-v" + (dot < 0 ? policyVersion : policyVersion.substring(0, dot));
    }
}
