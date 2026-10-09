package io.github.temporalrift.workbench.policy.application.query;

import java.util.List;

import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyDescriptor;
import io.github.temporalrift.workbench.policy.PolicyReferenceVerdict;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;

/** Resolves references against the baseline bundles, which declare no free parameters. */
public class BaselinePolicyCatalog implements PolicyCatalog {

    @Override
    public List<PolicyDescriptor> list() {
        return BaselinePolicies.all().stream()
                .map(bundle -> new PolicyDescriptor(
                        bundle.id(), bundle.version(), bundle.artifactDigest(), bundle.description()))
                .toList();
    }

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
