package io.github.temporalrift.workbench.analysis.application.query;

import io.github.temporalrift.workbench.analysis.application.port.in.GetRunEvaluationUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluation;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluator;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

/**
 * Evaluates a run variant on every read from the run's current cases, under the analysis version the criterion
 * names. Nothing is stored.
 */
public class GetRunEvaluationQueryHandler implements GetRunEvaluationUseCase {

    private final RunSource runs;
    private final BalanceCriteria criteria;
    private final CaseFactsProvider facts;

    public GetRunEvaluationQueryHandler(RunSource runs, BalanceCriteria criteria, CaseFactsProvider facts) {
        this.runs = runs;
        this.criteria = criteria;
        this.facts = facts;
    }

    @Override
    public RunEvaluation handle(Query query) {
        runs.experimentOf(query.runId()).orElseThrow(() -> new AnalysisResourceNotFoundException("Run", query.runId()));
        var criterion = criteria.find(query.criterionVersion())
                .orElseThrow(
                        () -> new AnalysisResourceNotFoundException("Balance criterion", query.criterionVersion()));
        var cases = runs.cases(query.runId()).stream()
                .filter(analyzedCase -> analyzedCase.variantLabel().equals(query.variantLabel()))
                .toList();
        if (cases.isEmpty()) {
            throw new AnalysisResourceNotFoundException("Variant", query.variantLabel());
        }
        var analysis = new AnalysisVersion(criterion.analysisVersion(), AnalysisVersion.CURRENT.seed());
        return RunEvaluator.evaluate(
                query.runId(),
                query.variantLabel(),
                criterion,
                query.mode(),
                analysis,
                cases,
                facts.factsOf(query.runId(), cases, analysis));
    }
}
