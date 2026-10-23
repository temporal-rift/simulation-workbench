package io.github.temporalrift.workbench.analysis.application.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import io.github.temporalrift.workbench.analysis.application.port.in.GetRunReportUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.port.out.ExperimentDefinitions;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.analysis.domain.report.CaseCounts;
import io.github.temporalrift.workbench.analysis.domain.report.ReportCalculator;
import io.github.temporalrift.workbench.analysis.domain.report.RunReport;

/**
 * Builds a run's report from its cases and the facts of its succeeded cases. A succeeded case's result never
 * changes, so a run whose case counts are unchanged has the report it had: the latest report of each of the most
 * recently read runs is kept, and concurrent requests for one state share a single computation.
 */
public class GetRunReportQueryHandler implements GetRunReportUseCase {

    static final int CACHED_RUNS = 32;

    private record State(AnalysisVersion analysis, CaseCounts counts) {}

    private record Cached(State state, CompletableFuture<RunReport> report) {}

    private final RunSource runs;
    private final ExperimentDefinitions experiments;
    private final CaseFactsProvider facts;
    private final Map<UUID, Cached> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Cached> eldest) {
            return size() > CACHED_RUNS;
        }
    });

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
        var state = new State(AnalysisVersion.CURRENT, CaseCounts.of(cases));
        CompletableFuture<RunReport> report;
        boolean owner;
        synchronized (cache) {
            var cached = cache.get(runId);
            owner = cached == null || !cached.state().equals(state);
            report = owner ? new CompletableFuture<>() : cached.report();
            if (owner) {
                cache.put(runId, new Cached(state, report));
            }
        }
        if (owner) {
            try {
                report.complete(ReportCalculator.report(
                        runId,
                        experiment.manifestDigest(),
                        state.analysis(),
                        cases,
                        facts.factsOf(runId, cases, state.analysis())));
            } catch (RuntimeException e) {
                cache.remove(runId, new Cached(state, report));
                report.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return report.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException failure ? failure : e;
        }
    }
}
