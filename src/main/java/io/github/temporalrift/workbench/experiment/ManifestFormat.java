package io.github.temporalrift.workbench.experiment;

import java.util.regex.Pattern;

/** The formats the published manifest contract fixes for portable references, for every module that holds one. */
public final class ManifestFormat {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern IMAGE_DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern SOURCE_REVISION = Pattern.compile("^[a-f0-9]{7,64}$");
    private static final Pattern CONTRACT_VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$");

    private ManifestFormat() {}

    /** A lowercase SHA-256 hex digest. */
    public static boolean isDigest(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    /** {@code sha256:} followed by a lowercase SHA-256 hex digest. */
    public static boolean isImageDigest(String value) {
        return value != null && IMAGE_DIGEST.matcher(value).matches();
    }

    /** 7 to 64 lowercase hex characters. */
    public static boolean isSourceRevision(String value) {
        return value != null && SOURCE_REVISION.matcher(value).matches();
    }

    /** An exact semantic version. */
    public static boolean isContractVersion(String value) {
        return value != null && CONTRACT_VERSION.matcher(value).matches();
    }
}
