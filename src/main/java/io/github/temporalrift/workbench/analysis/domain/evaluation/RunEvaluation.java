package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;

/**
 * The derived evaluation of one run variant against one criterion version.
 *
 * @param score null when the evaluation is invalid
 * @param confirmationSample null in screening mode
 */
public record RunEvaluation(
        UUID runId,
        String variantLabel,
        int criterionVersion,
        String criterionDigest,
        AnalysisVersion analysis,
        EvaluationMode mode,
        Validity validity,
        EvaluationVerdict verdict,
        Double score,
        ConfirmationSample confirmationSample,
        List<EvaluatedLine> lines) {

    public RunEvaluation {
        lines = List.copyOf(lines);
    }
}
