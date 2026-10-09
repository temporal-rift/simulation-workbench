package io.github.temporalrift.workbench.analysis.domain;

/**
 * The pinned identity of an analysis: its metric catalog and formulas, and the seed its resampling draws
 * from. The same version, seed and case results always reproduce the same numbers.
 */
public record AnalysisVersion(String version, long seed) {

    public static final AnalysisVersion CURRENT = new AnalysisVersion("1", 1L);

    /** The seed as a decimal unsigned 64-bit string. */
    public String seedText() {
        return Long.toUnsignedString(seed);
    }
}
