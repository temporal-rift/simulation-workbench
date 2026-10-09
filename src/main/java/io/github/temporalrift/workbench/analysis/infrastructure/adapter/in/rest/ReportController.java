package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.analysis.application.port.in.GetRunReportUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.ReportsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Report;

@RestController
class ReportController implements ReportsApi {

    private final GetRunReportUseCase getRunReport;

    ReportController(GetRunReportUseCase getRunReport) {
        this.getRunReport = getRunReport;
    }

    @Override
    public ResponseEntity<Report> getRunReport(UUID runId) {
        return ResponseEntity.ok(AnalysisApiMapper.report(getRunReport.handle(runId)));
    }
}
