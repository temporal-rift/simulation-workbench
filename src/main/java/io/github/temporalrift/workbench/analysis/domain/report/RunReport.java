package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;

/** The stratified report of a run; it stays incomplete while any requested case has not succeeded. */
public record RunReport(
        UUID runId, String manifestDigest, AnalysisVersion analysis, CaseCounts caseCounts, List<Cohort> cohorts) {

    public static final int FORMAT_VERSION = 1;

    public RunReport {
        cohorts = List.copyOf(cohorts);
    }

    public boolean complete() {
        return caseCounts.complete();
    }
}
