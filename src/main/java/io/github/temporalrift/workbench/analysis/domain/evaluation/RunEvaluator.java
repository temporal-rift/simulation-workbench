package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;

/**
 * Evaluates one run variant against one criterion version. Validity is decided first; a valid evaluation is rejected
 * by a failing gate, confirmed only in confirmation mode with a satisfied sample and no failing line that matters,
 * and otherwise unconfirmed. The same cases, criterion, mode and analysis always give the same evaluation.
 */
public final class RunEvaluator {

    private RunEvaluator() {}

    /**
     * @param variantCases the run's cases of the evaluated variant only
     * @param facts the facts of the succeeded cases
     */
    public static RunEvaluation evaluate(
            UUID runId,
            String variantLabel,
            BalanceCriterion criterion,
            EvaluationMode mode,
            AnalysisVersion analysis,
            List<AnalyzedCase> variantCases,
            Map<UUID, CaseFacts> facts) {
        var validity = EvidenceRules.validity(criterion, variantCases);
        var sample = mode == EvaluationMode.CONFIRMATION ? EvidenceRules.sample(criterion, variantCases) : null;
        var lines = LineExpansion.expand(criterion, variantCases, facts, analysis);
        var verdict = validity.valid() ? verdict(lines, sample) : EvaluationVerdict.INVALID;
        return new RunEvaluation(
                runId,
                variantLabel,
                criterion.version(),
                criterion.digest(),
                analysis,
                mode,
                validity,
                verdict,
                validity.valid() ? score(lines) : null,
                sample,
                lines);
    }

    private static EvaluationVerdict verdict(List<EvaluatedLine> lines, ConfirmationSample sample) {
        if (lines.stream().anyMatch(line -> line.role() == CriterionRole.GATE && line.verdict() == LineVerdict.FAILS)) {
            return EvaluationVerdict.REJECTED;
        }
        var pooledGatesMeet = lines.stream()
                .filter(line -> line.role() == CriterionRole.GATE && line.pooled())
                .allMatch(line -> line.verdict() == LineVerdict.MEETS);
        var scoredFails = lines.stream()
                .anyMatch(line -> line.role() == CriterionRole.SCORED && line.verdict() == LineVerdict.FAILS);
        return sample != null && sample.satisfied() && pooledGatesMeet && !scoredFails
                ? EvaluationVerdict.CONFIRMED
                : EvaluationVerdict.UNCONFIRMED;
    }

    /** The weighted sum of squared deviations of the scored lines, from point values. */
    private static double score(List<EvaluatedLine> lines) {
        var score = 0.0;
        for (var line : lines) {
            if (line.role() == CriterionRole.SCORED) {
                score += line.weight() * line.deviation() * line.deviation();
            }
        }
        return score;
    }
}
