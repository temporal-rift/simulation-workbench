package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy;

import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.policy.PolicyCatalog;

public class PolicyReferenceVerifierAdapter implements PolicyReferenceVerifier {

    private final PolicyCatalog catalog;

    public PolicyReferenceVerifierAdapter(PolicyCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Status verify(String id, String version, String artifactDigest, int parameterCount) {
        return switch (catalog.verify(id, version, artifactDigest, parameterCount)) {
            case VALID -> Status.VALID;
            case UNKNOWN_POLICY -> Status.UNKNOWN_POLICY;
            case DIGEST_MISMATCH -> Status.DIGEST_MISMATCH;
            case UNSUPPORTED_PARAMETERS -> Status.UNSUPPORTED_PARAMETERS;
        };
    }
}
