package io.github.temporalrift.workbench.policy;

/** Result of resolving a manifest policy reference against the defined policy bundles. */
public enum PolicyReferenceVerdict {
    VALID,
    UNKNOWN_POLICY,
    DIGEST_MISMATCH,
    UNSUPPORTED_PARAMETERS
}
