package io.github.temporalrift.workbench.analysis.application.query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        var runCases = new HashMap<UUID, List<AnalyzedCase>>();
        var baseline = side(definition.baseline(), runCases);
        var candidate = side(definition.candidate(), runCases);
        var known = new HashMap<>(facts.factsOf(definition.baseline().runId(), baseline, definition.analysis()));
        if (!definition.candidate().equals(definition.baseline())) {
            known.putAll(facts.factsOf(definition.candidate().runId(), candidate, definition.analysis()));
        }
        return ComparisonCalculator.compare(definition, baseline, candidate, known);
    }

    private List<AnalyzedCase> side(ComparisonSide side, Map<UUID, List<AnalyzedCase>> runCases) {
        return runCases.computeIfAbsent(side.runId(), runs::cases).stream()
                .filter(analyzedCase -> analyzedCase.variantLabel().equals(side.variantLabel()))
                .toList();
    }
}
