package io.github.temporalrift.workbench.execution.support;

import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;

/**
 * The frozen experiments as the production source reads them, except that a variant naming rules by digest alone
 * plays with empty rules under that digest. The scripted lanes ignore the rules a case carries, so the many tests
 * whose manifests name made-up digests need no baseline document.
 */
public class DigestOnlyRulesSource implements ExperimentSource {

    private final ExperimentSource delegate;

    public DigestOnlyRulesSource(ExperimentSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<Plan> plan(UUID experimentId) {
        return delegate.plan(experimentId);
    }

    @Override
    public Optional<Snapshot> snapshot(UUID experimentId) {
        return delegate.snapshot(experimentId);
    }

    @Override
    public Optional<CaseRules> rules(UUID experimentId, String variantLabel) {
        return delegate.rules(experimentId, variantLabel).or(() -> declared(experimentId, variantLabel));
    }

    private Optional<CaseRules> declared(UUID experimentId, String variantLabel) {
        return delegate.plan(experimentId).stream()
                .flatMap(plan -> plan.variants().stream())
                .filter(variant -> variant.label().equals(variantLabel))
                .findFirst()
                .map(variant -> new CaseRules(new TreeMap<>(), variant.bundle().rulesDigest()));
    }
}
