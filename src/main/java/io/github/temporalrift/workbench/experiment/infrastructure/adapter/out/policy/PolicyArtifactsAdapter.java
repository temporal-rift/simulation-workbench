package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy;

import java.util.Optional;

import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyArtifacts;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyDescriptor;

public class PolicyArtifactsAdapter implements PolicyArtifacts {

    private final PolicyCatalog catalog;

    public PolicyArtifactsAdapter(PolicyCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<String> artifactDigest(String id, String version) {
        return catalog.list().stream()
                .filter(policy -> policy.id().equals(id) && policy.version().equals(version))
                .map(PolicyDescriptor::artifactDigest)
                .findFirst();
    }
}
