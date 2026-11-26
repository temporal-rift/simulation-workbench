package io.github.temporalrift.workbench.policy.application.query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyDescriptor;
import io.github.temporalrift.workbench.policy.PolicyParameterSpaceDescriptor;
import io.github.temporalrift.workbench.policy.PolicyReferenceVerdict;
import io.github.temporalrift.workbench.policy.PolicyVerification;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;
import io.github.temporalrift.workbench.policy.domain.baseline.FactionSearchBundle;
import io.github.temporalrift.workbench.policy.domain.baseline.PolicyBundle;
import io.github.temporalrift.workbench.policy.domain.baseline.SearchParameters;
import io.github.temporalrift.workbench.policy.domain.baseline.SearchedSubsets;

/**
 * Resolves references against the baseline bundles, which declare no free parameters, and against
 * {@code faction-search}, whose parameters are declared by {@link SearchParameters}.
 */
public class BaselinePolicyCatalog implements PolicyCatalog {

    @Override
    public List<PolicyDescriptor> list() {
        return java.util.stream.Stream.concat(
                        BaselinePolicies.all().stream(), java.util.stream.Stream.of(FactionSearchBundle.defaults()))
                .map(bundle -> new PolicyDescriptor(
                        bundle.id(), bundle.version(), bundle.artifactDigest(), bundle.description()))
                .toList();
    }

    /** The one served version of the {@code faction-search} parameter space. */
    @Override
    public List<PolicyParameterSpaceDescriptor> parameterSpaces() {
        var parameters = SearchParameters.schema().stream()
                .map(spec -> new PolicyParameterSpaceDescriptor.Parameter(
                        spec.key(), spec.defaultValue(), spec.min(), spec.max(), spec.step()))
                .toList();
        var subsets = new java.util.LinkedHashMap<String, List<String>>();
        for (var faction : io.github.temporalrift.workbench.shared.game.Faction.values()) {
            subsets.put(faction.name(), SearchedSubsets.of(faction));
        }
        return List.of(new PolicyParameterSpaceDescriptor(
                1, FactionSearchBundle.ID, FactionSearchBundle.VERSION, parameters, subsets));
    }

    @Override
    public PolicyReferenceVerdict verify(String id, String version, String artifactDigest, int parameterCount) {
        if (isSearch(id, version)) {
            return parameterCount == 0
                    ? verify(id, version, artifactDigest, Map.<String, Object>of())
                            .verdict()
                    : PolicyReferenceVerdict.UNSUPPORTED_PARAMETERS;
        }
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

    @Override
    public PolicyVerification verify(String id, String version, String artifactDigest, Map<String, Object> parameters) {
        if (!isSearch(id, version)) {
            return new PolicyVerification(verify(id, version, artifactDigest, parameters.size()), null);
        }
        var named = new HashMap<String, Integer>();
        for (var entry : parameters.entrySet()) {
            var spec = SearchParameters.spec(entry.getKey());
            if (spec.isEmpty()) {
                return new PolicyVerification(PolicyReferenceVerdict.UNKNOWN_PARAMETER, entry.getKey());
            }
            if (!(entry.getValue() instanceof Integer || entry.getValue() instanceof Long)
                    || !spec.get().accepts(((Number) entry.getValue()).intValue())) {
                return new PolicyVerification(PolicyReferenceVerdict.INVALID_PARAMETER_VALUE, entry.getKey());
            }
            named.put(entry.getKey(), ((Number) entry.getValue()).intValue());
        }
        PolicyBundle bundle = FactionSearchBundle.of(named);
        return new PolicyVerification(
                bundle.artifactDigest().equals(artifactDigest)
                        ? PolicyReferenceVerdict.VALID
                        : PolicyReferenceVerdict.DIGEST_MISMATCH,
                null);
    }

    private static boolean isSearch(String id, String version) {
        return FactionSearchBundle.ID.equals(id) && FactionSearchBundle.VERSION.equals(version);
    }
}
