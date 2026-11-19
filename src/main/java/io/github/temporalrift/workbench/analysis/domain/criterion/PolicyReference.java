package io.github.temporalrift.workbench.analysis.domain.criterion;

/**
 * How a criterion names a bot policy: by policy id only, as in {@code faction-greedy}. A run's cohorts carry the id and
 * the version separately; the criterion resolves the id to the one bundle that has it, so a criterion and the bot a
 * run played cannot disagree on a version.
 */
public final class PolicyReference {

    private PolicyReference() {}

    public static String of(String policyId, String policyVersion) {
        return policyId;
    }
}
