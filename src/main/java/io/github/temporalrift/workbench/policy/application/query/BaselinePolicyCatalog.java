package io.github.temporalrift.workbench.policy.application.query;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Resolves references against the baseline bundles, which declare no free parameters, and against
 * {@code faction-search}, whose parameters are declared by {@link SearchParameters}.
 */
public class BaselinePolicyCatalog implements PolicyCatalog {

    @Override
    public List<PolicyDescriptor> list() {
        var baselines = BaselinePolicies.all().stream()
                .map(bundle -> new PolicyDescriptor(
                        bundle.id(), bundle.version(), bundle.artifactDigest(), bundle.description()));
        var search = FactionSearchBundle.defaults();
        var parameterized = new PolicyDescriptor(
                search.id(),
                search.version(),
                search.artifactDigest(),
                search.description(),
                SearchParameters.schema().stream()
                        .map(spec -> new PolicyParameterSpaceDescriptor.Parameter(
                                spec.key(), spec.defaultValue(), spec.min(), spec.max(), spec.step()))
                        .toList());
        return Stream.concat(baselines, Stream.of(parameterized)).toList();
    }

    /** The one served version of the {@code faction-search} parameter space. */
    @Override
    public List<PolicyParameterSpaceDescriptor> parameterSpaces() {
        var parameters = SearchParameters.schema().stream()
                .map(spec -> new PolicyParameterSpaceDescriptor.Parameter(
                        spec.key(), spec.defaultValue(), spec.min(), spec.max(), spec.step()))
                .toList();
        var subsets = new LinkedHashMap<String, List<String>>();
        for (var faction : Faction.values()) {
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
    public Optional<String> artifactDigest(String id, String version, Map<String, Object> parameters) {
        if (!isSearch(id, version)) {
            return Optional.empty();
        }
        var checked = check(parameters);
        return checked.failure() == null
                ? Optional.of(FactionSearchBundle.of(checked.named()).artifactDigest())
                : Optional.empty();
    }

    @Override
    public PolicyVerification verify(String id, String version, String artifactDigest, Map<String, Object> parameters) {
        if (!isSearch(id, version)) {
            return new PolicyVerification(verify(id, version, artifactDigest, parameters.size()), null);
        }
        var checked = check(parameters);
        if (checked.failure() != null) {
            return checked.failure();
        }
        PolicyBundle bundle = FactionSearchBundle.of(checked.named());
        return new PolicyVerification(
                bundle.artifactDigest().equals(artifactDigest)
                        ? PolicyReferenceVerdict.VALID
                        : PolicyReferenceVerdict.DIGEST_MISMATCH,
                null);
    }

    /** The parameters as integers, or the first refusal: the one place a value is checked and then narrowed. */
    private record Checked(Map<String, Integer> named, PolicyVerification failure) {}

    private static Checked check(Map<String, Object> parameters) {
        var named = new HashMap<String, Integer>();
        for (var entry : parameters.entrySet()) {
            var spec = SearchParameters.spec(entry.getKey());
            if (spec.isEmpty()) {
                return new Checked(
                        named, new PolicyVerification(PolicyReferenceVerdict.UNKNOWN_PARAMETER, entry.getKey()));
            }
            // The value is checked as given: narrowing a long first could fold an out-of-range value onto a valid one.
            if (!(entry.getValue() instanceof Integer || entry.getValue() instanceof Long)
                    || !fits(spec.get(), ((Number) entry.getValue()).longValue())) {
                return new Checked(
                        named, new PolicyVerification(PolicyReferenceVerdict.INVALID_PARAMETER_VALUE, entry.getKey()));
            }
            named.put(entry.getKey(), ((Number) entry.getValue()).intValue());
        }
        return new Checked(named, null);
    }

    private static boolean fits(SearchParameters.Spec spec, long value) {
        return value >= spec.min() && value <= spec.max() && spec.accepts((int) value);
    }

    private static boolean isSearch(String id, String version) {
        return FactionSearchBundle.ID.equals(id) && FactionSearchBundle.VERSION.equals(version);
    }
}
