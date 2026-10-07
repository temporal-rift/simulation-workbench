package io.github.temporalrift.workbench.policy.application.query;

import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyReferenceVerdict;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;

/** Resolves references against the baseline bundles, which declare no free parameters. */
public class BaselinePolicyCatalog implements PolicyCatalog {

    @Override
    public PolicyReferenceVerdict verify(String id, String version, String artifactDigest, int parameterCount) {
        return BaselinePolicies.find(id, version)
                .map(bundle -> {
                    if (!bundle.artifactDigest().equals(artifactDigest)) {
                        return PolicyReferenceVerdict.DIGEST_MISMATCH;
                    }
                    return parameterCount == 0
                            ? PolicyReferenceVerdict.VALID
                            : PolicyReferenceVerdict.UNSUPPORTED_PARAMETERS;
                })
                .orElse(PolicyReferenceVerdict.UNKNOWN_POLICY);
    }
}
