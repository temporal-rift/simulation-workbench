package io.github.temporalrift.workbench.execution.domain.run;

import java.util.regex.Pattern;

/**
 * The identity of a frozen configuration bundle: the effective rules and content digests a variant names and a lane
 * serves. A case plays only on a lane whose bundle equals its variant's.
 */
public record BundleDigests(String rulesDigest, String contentDigest) {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

    public BundleDigests {
        if (!isDigest(rulesDigest)) {
            throw new IllegalArgumentException("rulesDigest must be 64 lowercase hex characters: " + rulesDigest);
        }
        if (!isDigest(contentDigest)) {
            throw new IllegalArgumentException("contentDigest must be 64 lowercase hex characters: " + contentDigest);
        }
    }

    /** Whether the value is a lowercase hex SHA-256 digest. */
    public static boolean isDigest(String value) {
        return value != null && SHA256.matcher(value).matches();
    }
}
