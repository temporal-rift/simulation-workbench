package io.github.temporalrift.workbench.analysis.application.query;

import java.util.HashMap;
import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonCalculator;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonDefinition;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonResult;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonSide;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

/** Computes a comparison from the two sides' current case results. */
public class ComparisonEvaluator {

    private final RunSource runs;
    private final CaseFactsProvider facts;

    public ComparisonEvaluator(RunSource runs, CaseFactsProvider facts) {
        this.runs = runs;
        this.facts = facts;
    }

    public ComparisonResult evaluate(ComparisonDefinition definition) {
        var baseline = side(definition.baseline());
        var candidate = side(definition.candidate());
        var known = new HashMap<>(facts.factsOf(definition.baseline().runId(), baseline));
        known.putAll(facts.factsOf(definition.candidate().runId(), candidate));
        return ComparisonCalculator.compare(definition, baseline, candidate, known);
    }

    private List<AnalyzedCase> side(ComparisonSide side) {
        return runs.cases(side.runId()).stream()
                .filter(analyzedCase -> analyzedCase.variantLabel().equals(side.variantLabel()))
                .toList();
    }
}
