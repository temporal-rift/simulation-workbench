package io.github.temporalrift.workbench.experiment.domain.port.out;

/** Driven port resolving a manifest policy reference against the defined policy bundles. */
public interface PolicyReferenceVerifier {

    Status verify(String id, String version, String artifactDigest, int parameterCount);

    enum Status {
        VALID,
        UNKNOWN_POLICY,
        DIGEST_MISMATCH,
        UNSUPPORTED_PARAMETERS
    }
}
