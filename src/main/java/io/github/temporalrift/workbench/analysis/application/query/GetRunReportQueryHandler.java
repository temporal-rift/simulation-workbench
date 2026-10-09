package io.github.temporalrift.workbench.analysis.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.analysis.application.port.in.GetRunReportUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.port.out.ExperimentDefinitions;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.analysis.domain.report.ReportCalculator;
import io.github.temporalrift.workbench.analysis.domain.report.RunReport;

/** Builds a run's report from its cases and the facts of its succeeded cases. */
public class GetRunReportQueryHandler implements GetRunReportUseCase {

    private final RunSource runs;
    private final ExperimentDefinitions experiments;
    private final CaseFactsProvider facts;

    public GetRunReportQueryHandler(RunSource runs, ExperimentDefinitions experiments, CaseFactsProvider facts) {
        this.runs = runs;
        this.experiments = experiments;
        this.facts = facts;
    }

    @Override
    public RunReport handle(UUID runId) {
        var experimentId =
                runs.experimentOf(runId).orElseThrow(() -> new AnalysisResourceNotFoundException("Run", runId));
        var experiment = experiments
                .find(experimentId)
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Experiment", experimentId));
        var cases = runs.cases(runId);
        return ReportCalculator.report(
                runId,
                experiment.manifestDigest(),
                AnalysisVersion.CURRENT,
                cases,
                facts.factsOf(runId, cases, AnalysisVersion.CURRENT));
    }
}
