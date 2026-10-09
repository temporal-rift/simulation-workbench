package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;

/**
 * A stored comparison: its two sides, the variant differences it declares, and the analysis version and seed
 * pinned when it was created. Its numbers are recomputed from current case results on every read.
 */
public record ComparisonDefinition(
        UUID comparisonId,
        ComparisonSide baseline,
        ComparisonSide candidate,
        List<DeclaredDifference> declaredDifferences,
        AnalysisVersion analysis) {

    public ComparisonDefinition {
        Objects.requireNonNull(comparisonId, "comparisonId");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(analysis, "analysis");
        declaredDifferences = List.copyOf(declaredDifferences);
    }

    /**
     * Defines a comparison of two sides whose frozen experiments are equal in every gameplay definition.
     *
     * @throws InvalidComparisonException when both sides are the same or a variant is not in its experiment
     * @throws IncomparableRunsException when a gameplay definition differs, naming every one that does
     */
    public static ComparisonDefinition define(
            UUID comparisonId,
            ComparisonSide baseline,
            ExperimentDefinition baselineExperiment,
            ComparisonSide candidate,
            ExperimentDefinition candidateExperiment,
            AnalysisVersion analysis) {
        if (baseline.equals(candidate)) {
            throw new InvalidComparisonException("The baseline and the candidate are the same run and variant");
        }
        var baselineVariant = variant(baselineExperiment, baseline);
        var candidateVariant = variant(candidateExperiment, candidate);
        var differing = new ArrayList<String>();
        baselineExperiment.gameplay().forEach((definition, text) -> {
            if (!text.equals(candidateExperiment.gameplay().get(definition))) {
                differing.add(definition);
            }
        });
        candidateExperiment.gameplay().keySet().stream()
                .filter(definition -> !baselineExperiment.gameplay().containsKey(definition))
                .forEach(differing::add);
        if (!differing.isEmpty()) {
            throw new IncomparableRunsException(differing);
        }
        var declared = new ArrayList<DeclaredDifference>();
        if (!baselineVariant.rules().equals(candidateVariant.rules())) {
            declared.add(
                    new DeclaredDifference(DifferenceField.RULES, baselineVariant.rules(), candidateVariant.rules()));
        }
        if (!baselineVariant.content().equals(candidateVariant.content())) {
            declared.add(new DeclaredDifference(
                    DifferenceField.CONTENT, baselineVariant.content(), candidateVariant.content()));
        }
        return new ComparisonDefinition(comparisonId, baseline, candidate, declared, analysis);
    }

    private static ExperimentDefinition.VariantDigests variant(ExperimentDefinition experiment, ComparisonSide side) {
        var digests = experiment.variants().get(side.variantLabel());
        if (digests == null) {
            throw new InvalidComparisonException(
                    "Run " + side.runId() + " has no variant '" + side.variantLabel() + "'");
        }
        return digests;
    }
}
