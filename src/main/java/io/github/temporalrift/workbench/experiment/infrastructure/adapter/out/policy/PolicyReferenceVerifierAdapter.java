package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy;

import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.policy.PolicyCatalog;

public class PolicyReferenceVerifierAdapter implements PolicyReferenceVerifier {

    private final PolicyCatalog catalog;

    public PolicyReferenceVerifierAdapter(PolicyCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Result verify(String id, String version, String artifactDigest, java.util.Map<String, Object> parameters) {
        var verification = catalog.verify(id, version, artifactDigest, parameters);
        return new Result(status(verification.verdict()), verification.parameter());
    }

    @Override
    public Status verify(String id, String version, String artifactDigest, int parameterCount) {
        return status(catalog.verify(id, version, artifactDigest, parameterCount));
    }

    private static Status status(io.github.temporalrift.workbench.policy.PolicyReferenceVerdict verdict) {
        return switch (verdict) {
            case VALID -> Status.VALID;
            case UNKNOWN_POLICY -> Status.UNKNOWN_POLICY;
            case DIGEST_MISMATCH -> Status.DIGEST_MISMATCH;
            case UNSUPPORTED_PARAMETERS -> Status.UNSUPPORTED_PARAMETERS;
            case UNKNOWN_PARAMETER -> Status.UNKNOWN_PARAMETER;
            case INVALID_PARAMETER_VALUE -> Status.INVALID_PARAMETER_VALUE;
        };
    }
}
