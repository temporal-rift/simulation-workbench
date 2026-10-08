package io.github.temporalrift.workbench.execution.domain.reproduction;

/**
 * The artifacts a case would be reproduced from are missing or no longer match their content addresses
 * (published as {@code MANIFEST_MISMATCH}).
 */
public class ManifestMismatchException extends RuntimeException {

    public ManifestMismatchException(String reason) {
        super(reason);
    }
}
