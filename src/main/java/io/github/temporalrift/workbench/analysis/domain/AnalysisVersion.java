package io.github.temporalrift.workbench.analysis.domain;

import java.util.Set;

/**
 * The pinned identity of an analysis: its metric catalog and formulas, and the seed its resampling draws
 * from. The same version, seed and case results always reproduce the same numbers.
 */
public record AnalysisVersion(String version, long seed) {

    public static final AnalysisVersion CURRENT = new AnalysisVersion("5", 1L);

    private static final Set<String> SUPPORTED = Set.of("1", "2", "3", "4", "5");
    private static final int FAILURE_SHARE_SINCE = 2;
    private static final String RESAMPLING_KEY = "1";
    private static final int DIAGNOSTICS_SINCE = 3;
    private static final String FACTS_VERSION = "1";
    private static final String DIAGNOSTIC_FACTS_VERSION = "2";
    private static final int BET_SURVIVAL_SINCE = 4;
    private static final String BET_SURVIVAL_FACTS_VERSION = "3";
    private static final int WIN_CONDITIONS_SINCE = 5;
    private static final String WIN_CONDITIONS_FACTS_VERSION = "4";

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
        return RESAMPLING_KEY;
    }

    /** Whether reports of this version carry the balance diagnostics: points by reason, win paths, bets, cascades. */
    public boolean reportsDiagnostics() {
        return Integer.parseInt(version) >= DIAGNOSTICS_SINCE;
    }

    /** Whether reports of this version carry the bet survival metrics: bets placed, scored and won per faction. */
    public boolean reportsBetSurvival() {
        return Integer.parseInt(version) >= BET_SURVIVAL_SINCE;
    }

    /** Whether reports of this version carry {@code win_condition_rate}: wins by every condition the winner met. */
    public boolean reportsWinConditions() {
        return Integer.parseInt(version) >= WIN_CONDITIONS_SINCE;
    }

    /** The version under which facts are stored: versions that report more need the facts they derive from. */
    public String factsVersion() {
        if (reportsWinConditions()) {
            return WIN_CONDITIONS_FACTS_VERSION;
        }
        if (reportsBetSurvival()) {
            return BET_SURVIVAL_FACTS_VERSION;
        }
        return reportsDiagnostics() ? DIAGNOSTIC_FACTS_VERSION : FACTS_VERSION;
    }
}
