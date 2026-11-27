package io.github.temporalrift.workbench.experiment.domain.port.out;

import java.util.Map;

/** Driven port resolving a manifest policy reference against the defined policy bundles. */
public interface PolicyReferenceVerifier {

    Status verify(String id, String version, String artifactDigest, int parameterCount);

    /** Resolves an entry that names parameters; the default reads only how many, as the baselines declare none. */
    default Result verify(String id, String version, String artifactDigest, Map<String, Object> parameters) {
        return new Result(verify(id, version, artifactDigest, parameters.size()), null);
    }

    enum Status {
        VALID,
        UNKNOWN_POLICY,
        DIGEST_MISMATCH,
        UNSUPPORTED_PARAMETERS,
        UNKNOWN_PARAMETER,
        INVALID_PARAMETER_VALUE
    }

    /** The status and, for an unknown or invalid parameter, its name. */
    record Result(Status status, String parameter) {}
}
