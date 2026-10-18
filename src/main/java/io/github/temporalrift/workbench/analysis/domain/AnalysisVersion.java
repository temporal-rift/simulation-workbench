package io.github.temporalrift.workbench.analysis.domain;

import java.util.Set;

/**
 * The pinned identity of an analysis: its metric catalog and formulas, and the seed its resampling draws
 * from. The same version, seed and case results always reproduce the same numbers.
 */
public record AnalysisVersion(String version, long seed) {

    public static final AnalysisVersion CURRENT = new AnalysisVersion("2", 1L);

    private static final Set<String> SUPPORTED = Set.of("1", "2");
    private static final int FAILURE_SHARE_SINCE = 2;

    /** The seed as a decimal unsigned 64-bit string. */
    public String seedText() {
        return Long.toUnsignedString(seed);
    }

    public boolean supported() {
        return SUPPORTED.contains(version);
    }

    /** Whether reports of this version carry {@code failure_share}. */
    public boolean reportsFailureShare() {
        return Integer.parseInt(version) >= FAILURE_SHARE_SINCE;
    }

    /** What the resampling streams are keyed by: the scheme, which no version has changed, not the version. */
    public String resamplingKey() {
        return "1";
    }

    /** The version under which facts are stored: extraction is the same for every supported version. */
    public String factsVersion() {
        return "1";
    }
}
