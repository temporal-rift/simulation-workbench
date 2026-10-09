package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.report.RunReport;

/** Reads a run's stratified statistical report. */
public interface GetRunReportUseCase {

    RunReport handle(UUID runId);
}
